package de.embl.rieslab.emu.micromanager.mmproperties;

import com.google.common.eventbus.Subscribe;
import de.embl.rieslab.emu.controller.log.Logger;
import java.util.HashMap;
import java.util.Iterator;
import mmcorej.CMMCore;
import mmcorej.StrVector;
import org.micromanager.Studio;
import org.micromanager.events.ConfigGroupChangedEvent;
import org.micromanager.events.PropertiesChangedEvent;
import org.micromanager.events.PropertyChangedEvent;

/**
 * Class referencing the devices loaded in Micro-Manager and their device properties.
 *
 * @author Joran Deschamps
 */
@SuppressWarnings("rawtypes")
public class MMPropertiesRegistry {

   private final Studio studio_;
   private final CMMCore core_;
   private final Logger logger_;
   private final HashMap<String, MMDevice> devices_;
   private final HashMap<String, MMProperty> properties_;
   private boolean registeredForEvents_ = false;

   /**
    * Constructor. Calls a private initialization method to extract the devices and their
    * properties. It ignores "COM" devices.
    *
    * @param studio MM studio instance
    * @param logger EMU logger
    */
   public MMPropertiesRegistry(Studio studio, Logger logger) {
      studio_ = studio;
      core_ = studio.getCMMCore();
      logger_ = logger;
      devices_ = new HashMap<>();
      properties_ = new HashMap<>();

      initialize();
   }

   private void initialize() {
      StrVector deviceList = core_.getLoadedDevices();
      StrVector propertyList;
      MMPropertyFactory builder = new MMPropertyFactory(core_, logger_);

      for (String device : deviceList) {
         if (!((device.length() >= 3) && device.startsWith("COM"))) {
            MMDevice dev = new MMDevice(device);
            try {
               propertyList = core_.getDevicePropertyNames(device);
               for (String property : propertyList) {
                  MMProperty prop = builder.getNewProperty(device, property);
                  dev.registerProperty(prop);
                  properties_.put(prop.getHash(), prop);
               }
            } catch (Exception e) {
               studio_.logs().logError(e);
            }
            devices_.put(dev.getDeviceLabel(), dev);
         }
      }
   }

   /**
    * Returns the property with hash {@code propertyHash} (see {@link MMProperty} for the
    * definition of the hash).
    *
    * @param propertyHash Hash of the requested property.
    * @return Micro-manager property.
    */
   public MMProperty getProperty(String propertyHash) {
      return properties_.get(propertyHash);
   }

   /**
    * Returns the map of {@link MMProperty} indexed by their hash.
    *
    * @return Micro-manager properties map.
    */
   public HashMap<String, MMProperty> getProperties() {
      return properties_;
   }

   /**
    * Returns the device with label {@code deviceLabel}.
    *
    * @param deviceLabel Label of the device
    * @return Micro-manager device or null if the device does not exists.
    */
   public MMDevice getDevice(String deviceLabel) {
      return devices_.get(deviceLabel);
   }

   /**
    * Returns the map of {@link MMDevice} indexed by their label.
    *
    * @return Micro-manager devices map.
    */
   public HashMap<String, MMDevice> getDevices() {
      return devices_;
   }

   /**
    * Returns the names of the devices in a String array.
    *
    * @return Array of device names
    */
   public String[] getDevicesList() {
      String[] s = new String[devices_.size()];
      devices_.keySet().toArray(s);
      return s;
   }

   /**
    * Adds a device to the map of devices.
    *
    * @param device Device to be added.
    */
   public void addMMDevice(MMDevice device) {
      if (device.getProperties().size() > 0) {
         devices_.put(device.getDeviceLabel(), device);
         properties_.putAll(device.getProperties());
      }
   }

   /**
    * Checks if {@code hash} corresponds to a known Micro-manager device property.
    *
    * @param hash Hash to be tested
    * @return True if the hash corresponds to a device property, false otherwise.
    */
   public boolean isProperty(String hash) {
      return properties_.containsKey(hash);
   }

   /**
    * Clears all Micro-manager device property listeners (which are of the class UIProperty).
    * Called during reloading of the system by the
    * {@link de.embl.rieslab.emu.controller.SystemController}.
    */
   public void clearAllListeners() {
      Iterator<String> it = properties_.keySet().iterator();
      while (it.hasNext()) {
         properties_.get(it.next()).clearAllListeners();
      }
   }

   /**
    * Subscribes the registry to the Micro-Manager event bus so that the EMU GUI refreshes when
    * device properties or configuration groups are changed outside of EMU (e.g. via pycro-manager,
    * the Property Browser, or hardware-initiated changes). Should be called once the registry,
    * including the configuration groups, has been fully populated. Calling it more than once has
    * no effect.
    */
   public void registerForEvents() {
      if (!registeredForEvents_) {
         studio_.events().registerForEvents(this);
         registeredForEvents_ = true;
      }
   }

   /**
    * Unsubscribes the registry from the Micro-Manager event bus. Called when EMU shuts down to
    * avoid leaking a dead subscriber. Calling it when not registered has no effect.
    */
   public void unregisterFromEvents() {
      if (registeredForEvents_) {
         studio_.events().unregisterForEvents(this);
         registeredForEvents_ = false;
      }
   }

   /**
    * Handles a single device property change originating outside of EMU (e.g. a pycro-manager
    * {@code core.set_property(device, property, value)} call, the Property Browser, or hardware).
    * Re-reads the authoritative value from the core and notifies all UIProperty listeners.
    *
    * <p>This callback is posted on the EDT by
    * {@link org.micromanager.events.internal.CoreEventCallback}.
    *
    * @param event Micro-Manager property changed event.
    */
   @Subscribe
   public void onPropertyChanged(PropertyChangedEvent event) {
      String hash = event.getDevice() + "-" + event.getProperty();
      MMProperty prop = properties_.get(hash);
      if (prop != null) {
         // source == null => no originating UIProperty to exclude, notify all listeners.
         prop.updateMMProperty();
      }
   }

   /**
    * Handles the coarse "properties changed" callback by refreshing every tracked device property.
    * This catches bulk changes and devices that only emit the global callback rather than a
    * per-property one. Configuration groups are handled separately by
    * {@link #onConfigGroupChanged(ConfigGroupChangedEvent)}.
    *
    * @param event Micro-Manager properties changed event.
    */
   @Subscribe
   public void onPropertiesChanged(PropertiesChangedEvent event) {
      for (MMProperty prop : properties_.values()) {
         if (!(prop instanceof PresetGroupAsMMProperty)) {
            prop.updateMMProperty();
         }
      }
   }

   /**
    * Handles a configuration group / preset change originating outside of EMU (e.g. a
    * pycro-manager {@code core.set_config(group, preset)} call). Refreshes the corresponding
    * {@link PresetGroupAsMMProperty} so that the UIProperty bound to the group updates.
    *
    * @param event Micro-Manager configuration group changed event.
    */
   @Subscribe
   public void onConfigGroupChanged(ConfigGroupChangedEvent event) {
      String hash = PresetGroupAsMMProperty.KEY_MMCONFDEVICE + "-" + event.getGroupName();
      MMProperty prop = properties_.get(hash);
      if (prop != null) {
         prop.updateMMProperty();
      }
   }
}

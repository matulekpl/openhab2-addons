/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.samsungtv.internal.handler;

import static org.openhab.binding.samsungtv.internal.SamsungTvBindingConstants.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.discovery.DiscoveryListener;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryServiceRegistry;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.io.transport.upnp.UpnpIOService;
import org.jupnp.UpnpService;
import org.jupnp.model.meta.Device;
import org.jupnp.model.meta.RemoteDevice;
import org.openhab.binding.samsungtv.internal.config.SamsungTvConfiguration;
import org.openhab.binding.samsungtv.internal.service.RemoteControllerService;
import org.openhab.binding.samsungtv.internal.service.ServiceFactory;
import org.openhab.binding.samsungtv.internal.service.api.EventListener;
import org.openhab.binding.samsungtv.internal.service.api.SamsungTvService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link SamsungTvHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Pauli Anttila - Initial contribution
 * @author Martin van Wingerden - Some changes for non-UPnP configured devices
 * @author Arjan Mels - Remove RegistryListener, manually create RemoteService in all circumstances, add sending of WOL
 *         package to power on TV
 */
public class SamsungTvHandler extends BaseThingHandler implements DiscoveryListener, EventListener {

    private Logger logger = LoggerFactory.getLogger(SamsungTvHandler.class);

    /* Global configuration for Samsung TV Thing */
    private SamsungTvConfiguration configuration;

    private UpnpIOService upnpIOService;
    private DiscoveryServiceRegistry discoveryServiceRegistry;
    private UpnpService upnpService;

    private ThingUID upnpThingUID = null;

    /* Samsung TV services */
    private final List<SamsungTvService> services = new CopyOnWriteArrayList<>();

    /* Store powerState to be able to restore upon new link */
    private boolean powerState = false;

    public SamsungTvHandler(Thing thing, UpnpIOService upnpIOService, DiscoveryServiceRegistry discoveryServiceRegistry,
            UpnpService upnpService) {
        super(thing);

        logger.debug("Create a Samsung TV Handler for thing '{}'", getThing().getUID());

        if (upnpIOService != null) {
            this.upnpIOService = upnpIOService;
        } else {
            logger.debug("upnpIOService not set.");
        }

        if (upnpService != null) {
            this.upnpService = upnpService;
        } else {
            logger.debug("upnpService not set.");
        }

        if (discoveryServiceRegistry != null) {
            this.discoveryServiceRegistry = discoveryServiceRegistry;
        } else {
            logger.debug("discoveryServiceRegistry not set.");
        }

    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("Received channel: {}, command: {}", channelUID, command);

        String channel = channelUID.getId();

        // Delegate command to correct service
        for (SamsungTvService service : services) {
            for (String s : service.getSupportedChannelNames()) {
                if (channel.equals(s)) {
                    service.handleCommand(channel, command);
                    return;
                }
            }
        }

        // if power command failed: try to use WOL
        if ((channel.equals(POWER) || channel.equals(ART_MODE)) && OnOffType.ON.equals(command)) {
            sendWOLandResendCommand(channel, command);
        } else {
            logger.warn("Channel '{}' not supported", channelUID);
        }
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        logger.trace("channelLinked: {}", channelUID);

        updateState(POWER, getPowerState() ? OnOffType.ON : OnOffType.OFF);

        for (SamsungTvService service : services) {
            if (service != null) {
                service.clearCache();
            }
        }
    }

    private synchronized void setPowerState(boolean state) {
        powerState = state;
    }

    private synchronized boolean getPowerState() {
        return powerState;
    }

    @Override
    public void initialize() {
        updateStatus(ThingStatus.OFFLINE);

        configuration = getConfigAs(SamsungTvConfiguration.class);

        logger.debug("Initializing Samsung TV handler for uid '{}'", getThing().getUID());

        if (discoveryServiceRegistry != null) {
            discoveryServiceRegistry.addDiscoveryListener(this);
        }

        checkAndCreateServices();

        if (configuration.macAddress == null || configuration.macAddress.isEmpty()) {
            try {
                Process proc = Runtime.getRuntime().exec("arping -r -c 1 -C 1 " + configuration.hostName);
                proc.waitFor();
                BufferedReader stdInput = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                configuration.macAddress = stdInput.readLine();
                getConfig().put(SamsungTvConfiguration.MAC_ADDRESS, configuration.macAddress);
                logger.info("MAC address of host {} is {}", configuration.hostName, configuration.macAddress);

            } catch (Exception e) {
                logger.info("Problem getting MAC address: {}", e.getMessage());
            }
        }
    }

    @Override
    public void dispose() {
        logger.debug("Disposing SamsungTvHandler");
        if (discoveryServiceRegistry != null) {
            discoveryServiceRegistry.removeDiscoveryListener(this);
        }
        shutdown();
        putOffline();
    }

    private void shutdown() {
        logger.debug("Shutdown all Samsung services");
        for (SamsungTvService service : services) {
            stopService(service);
        }
        services.clear();
    }

    private synchronized void putOnline() {
        setPowerState(true);
        updateStatus(ThingStatus.ONLINE);
        updateState(POWER, OnOffType.ON);
    }

    private synchronized void putOffline() {
        setPowerState(false);
        updateStatus(ThingStatus.OFFLINE);
        updateState(POWER, OnOffType.OFF);
        updateState(ART_MODE, OnOffType.OFF);
    }

    @Override
    public synchronized void valueReceived(String variable, State value) {
        logger.debug("Received value '{}':'{}' for thing '{}'", variable, value, this.getThing().getUID());

        updateState(variable, value);
        if (POWER.equals(variable)) {
            setPowerState(OnOffType.ON.equals(value));
        }
    }

    @Override
    public void reportError(ThingStatusDetail statusDetail, String message, Throwable e) {
        logger.info("Error was reported: {}", message, e);
    }

    /**
     * One Samsung TV contains several UPnP devices. Samsung TV is discovered by
     * Media Renderer UPnP device. This function tries to find another UPnP
     * devices related to same Samsung TV and create handler for those.
     */
    private void checkAndCreateServices() {
        logger.debug("Check and create missing UPnP services");

        for (Device device : upnpService.getRegistry().getDevices()) {
            createService((RemoteDevice) device);
        }

        checkCreateManualConnection();
    }

    private synchronized void createService(RemoteDevice device) {
        if (configuration != null) {
            if (configuration.hostName.equals(device.getIdentity().getDescriptorURL().getHost())) {
                String modelName = device.getDetails().getModelDetails().getModelName();
                String udn = device.getIdentity().getUdn().getIdentifierString();
                String type = device.getType().getType();

                SamsungTvService existingService = findServiceInstance(type);

                if (existingService == null || !existingService.isUpnp()) {
                    SamsungTvService newService = ServiceFactory.createService(type, upnpIOService, udn,
                            configuration.refreshInterval, configuration.hostName, configuration.port);

                    if (newService != null) {
                        if (existingService != null) {
                            stopService(existingService);
                            startService(newService);
                            logger.debug("Restarting service in UPnP mode for: {}, {} ({})", modelName, type, udn);
                        } else {
                            startService(newService);
                            logger.debug("Started service for: {}, {} ({})", modelName, type, udn);
                        }
                    } else {
                        logger.trace("Skipping unknown UPnP service: {}, {} ({})", modelName, type, udn);
                    }

                } else {
                    logger.debug("Service rediscoved, clearing caches: {}, {} ({})", modelName, type, udn);
                    existingService.clearCache();
                }
                putOnline();
            } else {
                // logger.trace("Ignore device={}", device);
            }
        } else {
            logger.error("Thing not yet initialized");
        }
    }

    private SamsungTvService findServiceInstance(String serviceName) {
        Class<? extends SamsungTvService> cl = ServiceFactory.getClassByServiceName(serviceName);

        for (SamsungTvService service : services) {
            if (service.getClass() == cl) {
                return service;
            }
        }
        return null;
    }

    private synchronized void checkCreateManualConnection() {
        try {
            // create remote service manually if it does not yet exist

            RemoteControllerService service = (RemoteControllerService) findServiceInstance(
                    RemoteControllerService.SERVICE_NAME);
            if (service == null) {
                service = RemoteControllerService.createNonUpnpService(configuration.hostName, configuration.port);
            }

            startService(service);
            if (service.checkConnection()) {
                putOnline();
            } else {
                putOffline();
                stopService(service);
            }
        } catch (RuntimeException e) {
            logger.warn("Catching all exceptions because otherwise the thread would silently fail", e);
        }
    }

    private synchronized void startService(SamsungTvService service) {
        if (service != null) {
            service.addEventListener(this);
            service.start();
            services.add(service);
        }
    }

    private synchronized void stopService(SamsungTvService service) {
        if (service != null) {
            service.stop();
            service.removeEventListener(this);
            services.remove(service);
        }
    }

    @Override
    public void thingDiscovered(DiscoveryService source, DiscoveryResult result) {

        if (configuration.hostName.equals(result.getProperties().get(SamsungTvConfiguration.HOST_NAME))) {
            logger.debug("thingDiscovered: {}, {}", result.getProperties().get(SamsungTvConfiguration.HOST_NAME),
                    result);
            /*
             * SamsungTV discovery services creates thing UID from UPnP UDN.
             * When thing is generated manually, thing UID may not match UPnP UDN, so store it for later use (e.g.
             * thingRemoved).
             */
            upnpThingUID = result.getThingUID();
            logger.debug("thingDiscovered, thingUID={}, discoveredUID={}", this.getThing().getUID(), upnpThingUID);
            checkAndCreateServices();
        }
    }

    @Override
    public void thingRemoved(DiscoveryService source, ThingUID thingUID) {
        if (thingUID.equals(upnpThingUID)) {
            logger.debug("Thing Removed: {}", thingUID);
            shutdown();
            putOffline();
        }
    }

    @Override
    public @Nullable Collection<@NonNull ThingUID> removeOlderResults(DiscoveryService source, long timestamp,
            @Nullable Collection<@NonNull ThingTypeUID> thingTypeUIDs, @Nullable ThingUID bridgeUID) {
        return null;
    }

    /**
     * Send single WOL (Wake On Lan) package on all interfaces
     */
    void sendWOLAllInterfaces() {
        byte[] bytes = getWOLPackage(configuration.macAddress);

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback()) {
                    continue; // Do not want to use the loopback interface.
                }
                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress broadcast = interfaceAddress.getBroadcast();
                    if (broadcast == null) {
                        continue;
                    }

                    try {
                        InetAddress address = InetAddress.getByName(configuration.hostName);
                        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, address, 9);
                        DatagramSocket socket = new DatagramSocket();
                        socket.send(packet);
                        socket.close();
                    } catch (Exception e) {
                        logger.warn("Problem sending WOL packet to {} ({})", configuration.hostName,
                                configuration.macAddress);
                    }
                }
            }

        } catch (Exception e) {
            logger.warn("Problem with interface while sending WOL packet to {} ({})", configuration.hostName,
                    configuration.macAddress);
        }
    }

    /**
     * Send multiple WOL packets spaced with 100ms intervals and resend command
     *
     * @param channel Channel to resend command on
     * @param command Command to resend
     */
    private void sendWOLandResendCommand(String channel, Command command) {
        logger.info("Send WOL packet to {} ({})", configuration.hostName, configuration.macAddress);

        // send max 10 WOL packets with 100ms intervals

        scheduler.schedule(new Runnable() {
            int count = 0;

            @Override
            public void run() {
                count++;
                if (count < 10) {
                    sendWOLAllInterfaces();
                    scheduler.schedule(this, 100, TimeUnit.MILLISECONDS);
                }
            }
        }, 1, TimeUnit.MILLISECONDS);

        // after RemoteService up again to ensure state is properly set
        scheduler.schedule(new Runnable() {
            int count = 0;

            @Override
            public void run() {
                count++;
                if (count < 30) {
                    RemoteControllerService service = (RemoteControllerService) findServiceInstance(
                            RemoteControllerService.SERVICE_NAME);
                    if (service != null) {
                        logger.info("Service found after {} attempts: resend command {} to channel {}", count, command,
                                channel);
                        service.handleCommand(channel, command);
                    } else {
                        scheduler.schedule(this, 1000, TimeUnit.MILLISECONDS);
                    }
                } else {
                    logger.info("Service NOT found after {} attempts", count);
                }
            }

        }, 1000, TimeUnit.MILLISECONDS);
    }

    /**
     * Create WOL UDP package: 6 bytes 0xff and then 6 times the 6 byte mac address repeated
     *
     * @param macStr String representation of teh MAC address (either with : or -)
     * @return byte array with the WOL package
     * @throws IllegalArgumentException
     */
    private static byte[] getWOLPackage(String macStr) throws IllegalArgumentException {
        byte[] macBytes = new byte[6];
        String[] hex = macStr.split("(\\:|\\-)");
        if (hex.length != 6) {
            throw new IllegalArgumentException("Invalid MAC address.");
        }
        try {
            for (int i = 0; i < 6; i++) {
                macBytes[i] = (byte) Integer.parseInt(hex[i], 16);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid hex digit in MAC address.");
        }

        byte[] bytes = new byte[6 + 16 * macBytes.length];
        for (int i = 0; i < 6; i++) {
            bytes[i] = (byte) 0xff;
        }
        for (int i = 6; i < bytes.length; i += macBytes.length) {
            System.arraycopy(macBytes, 0, bytes, i, macBytes.length);
        }

        return bytes;
    }

}

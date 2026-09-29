package cn.cdi8.control;

import java.util.Map;

/** The engine knows commands and observations, never a device FSM implementation. */
public interface DeviceAdapter {
    Map<String,Object> snapshot(String device);
    Map<String,Object> send(String device, String commandId, String action, Map<String,Object> parameters);
    Map<String,Object> result(String device, String commandId);
    void cancel(String device, String commandId);
    void simulate(String device, String commandId, String outcome);
}

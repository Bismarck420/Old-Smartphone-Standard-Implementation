# OSSI manager discovery protocol v1

An ESP8266 manager advertises itself on its local Wi-Fi network using UDP broadcast.
The Android Client listens on UDP port `8266`; the Host reads the Client's current
manager list from `GET /managers` on port `8080`.

## Advertisement

- Destination: `255.255.255.255:8266` (UDP broadcast)
- Interval: every 3 seconds
- Encoding: UTF-8 JSON. Keep the packet below roughly 1200 bytes to avoid IP fragmentation.
- The packet's source IP is authoritative. An IP contained in the JSON is ignored.
- A manager is removed from the list when no packet has arrived for 12 seconds.

```json
{
  "protocol": "ossi-manager",
  "version": 1,
  "managerId": "esp8266-a1b2c3",
  "name": "Living room controller",
  "httpPort": 80,
  "devices": [
    {
      "id": "relay-1",
      "kind": "switch",
      "type": "RELAY",
      "name": "Ceiling light",
      "unit": "",
      "endpointPath": "/relay/1"
    },
    {
      "id": "dht22-temperature",
      "kind": "sensor",
      "type": "AMBIENT_TEMPERATURE",
      "name": "Room temperature",
      "unit": "°C",
      "endpointPath": "/sensor/dht22/temperature"
    }
  ]
}
```

`managerId` must be stable across restarts, for example `"esp8266-" + ESP.getChipId()`.
`name` should be human-readable. `httpPort` defaults to 80 when omitted.

Every physical or logical channel managed by the ESP has one stable entry in `devices`:

- `id`: stable and unique within this manager
- `kind`: `sensor` or `switch`
- `type`: machine-readable device type; use OSSI names such as
  `AMBIENT_TEMPERATURE`, `RELATIVE_HUMIDITY`, `LIGHT_SENSOR`, or `RELAY`
- `name`: human-readable channel name shown in the Android endpoint picker
- `unit`: sensor unit, empty for switches
- `endpointPath`: HTTP path for reading or controlling precisely this channel

## ESP8266 implementation contract

The future `advertise()` function in ESP8266-Sensorbuild should enable UDP broadcast,
serialize the manager and its registered devices, and send the payload every three seconds while
Wi-Fi is connected. Calling it from `loop()` is safe when it internally tracks its
last-send time with `millis()` and returns immediately between sends.

The ESP project should introduce a small common `ManagedDevice` base/interface plus
`ManagedSensor` and `ManagedSwitch` classes. Each class provides the six advertised
fields above. The manager owns a list of these devices and `advertise()` serializes it.

The selected device path is stored as the module's Wi-Fi endpoint. A switch must
accept the existing JSON POST command at its advertised `endpointPath`. A sensor path
should return its current numeric value and unit; the exact sensor response contract
can be added when remote sensor polling is implemented.

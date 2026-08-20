# ATAG ONE Binding

This binding integrates the [ATAG ONE](https://www.atag.nl/producten/thermostaten/atag-one) smart thermostat with openHAB via its local HTTP API, without requiring any cloud connection or MQTT broker.

## Supported Things

| Thing ID    | Description                              |
|-------------|------------------------------------------|
| `thermostat` | ATAG ONE thermostat (local LAN API) |

## Discovery

The thermostat broadcasts a UDP datagram on port 11000 approximately every 10 seconds.
The binding listens passively and creates an Inbox entry when it detects a device.
Discovery is optional — the Thing can also be created manually (see below).

## Pairing

The ATAG ONE requires a one-time pairing step.
After adding the Thing it will go `OFFLINE / CONFIGURATION_PENDING`.

1. Open the thermostat display.
1. Navigate to **Settings → Connected apps** and press **Accept**.

The Thing transitions to `ONLINE` within a few seconds.
On subsequent openHAB restarts the saved client ID is reused, so the press-Accept step is not repeated.

## Thing Configuration

| Parameter         | Type    | Required | Default | Description                                        |
|-------------------|---------|----------|---------|----------------------------------------------------|
| `hostname`        | text    | yes      | —       | IP address or hostname of the thermostat           |
| `port`            | integer | no       | `10000` | HTTP port of the local API                         |
| `refreshInterval` | integer | no       | `30`    | Poll interval in seconds                           |
| `clientId`        | text    | no       | auto    | Stable client identifier used for pairing (advanced) |

### Textual configuration example

```java
Thing atagone:thermostat:boiler "ATAG ONE" [
    hostname        = "192.168.1.42",
    refreshInterval = 30
]
```

`clientId` is omitted — the binding generates one on first pairing and persists it automatically.

## Channels

### Standard channels

| Channel ID              | Type                | RW | Description                                  |
|-------------------------|---------------------|----|----------------------------------------------|
| `room-temperature`      | `Number:Temperature` | R | Current room temperature                     |
| `target-temperature`    | `Number:Temperature` | RW | Target (setpoint) temperature                |
| `hvac-mode`             | `String`            | RW | `auto` or `heat`                             |
| `preset-mode`           | `String`            | RW | `standby`, `manual`, `automatic`, `vacation`, `extend`, `fireplace` |
| `preset-mode-duration`  | `Number:Time`       | R  | Remaining duration of current preset         |
| `ch-water-temperature`  | `Number:Temperature` | R | Central heating water temperature            |
| `ch-return-temperature` | `Number:Temperature` | R | Central heating return temperature           |
| `ch-water-pressure`     | `Number:Pressure`   | R  | CH circuit water pressure                    |
| `ch-setpoint`           | `Number:Temperature` | R | Active CH setpoint sent to boiler            |
| `dhw-temperature`       | `Number:Temperature` | R | Domestic hot water temperature               |
| `dhw-target-temperature`| `Number:Temperature` | RW | DHW target temperature                      |
| `dhw-mode`              | `String`            | RW | DHW operating mode                           |
| `outside-temperature`   | `Number:Temperature` | R | Outside temperature (signed, negative in winter) |
| `flame`                 | `Switch`            | R  | Burner flame active                          |
| `modulation-level`      | `Number:Dimensionless` | R | Burner modulation level (%)               |
| `burning-hours`         | `Number:Time`       | R  | Total burner hours                           |
| `burner-target`         | `String`            | R  | `none`, `ch`, or `dhw`                       |
| `vacation-start`        | `DateTime`          | RW | Vacation period start                        |
| `vacation-end`          | `DateTime`          | RW | Vacation period end                          |
| `vacation-temperature`  | `Number:Temperature` | RW | Setpoint during vacation                    |
| `extend-duration`       | `Number:Time`       | RW | Duration to extend current schedule (minutes)|
| `fireplace-duration`    | `Number:Time`       | RW | Fireplace mode duration (minutes)            |
| `weather-status`        | `String`            | R  | Weather compensation status                  |
| `device-errors`         | `String`            | R  | Active device error codes                    |
| `boiler-errors`         | `String`            | R  | Active boiler error codes                    |
| `time-to-target`        | `Number:Time`       | R  | Estimated time to reach target temperature   |

Advanced diagnostic channels are also available (visible when **Show advanced** is enabled in the UI).

## Vacation mode

Vacation mode lets you set a fixed low temperature for a defined period.
Set `vacation-start` and `vacation-end` first, then set `preset-mode` to `vacation`.

```text
Item atagone_vacation_start   "Vacation start"  { channel="atagone:thermostat:boiler:vacation-start" }
Item atagone_vacation_end     "Vacation end"    { channel="atagone:thermostat:boiler:vacation-end" }
Item atagone_preset           "Preset mode"     { channel="atagone:thermostat:boiler:preset-mode" }
```

Writing `end <= start` is rejected with a warning; the device is left unchanged.

## Full example

### `atagone.items`

```text
Number:Temperature  CH_Room_Temp        "Room [%.1f °C]"    { channel="atagone:thermostat:boiler:room-temperature" }
Number:Temperature  CH_Target_Temp      "Target [%.1f °C]"  { channel="atagone:thermostat:boiler:target-temperature" }
String              CH_Preset           "Preset [%s]"       { channel="atagone:thermostat:boiler:preset-mode" }
DateTime            CH_Vacation_Start   "Vacation start"    { channel="atagone:thermostat:boiler:vacation-start" }
DateTime            CH_Vacation_End     "Vacation end"      { channel="atagone:thermostat:boiler:vacation-end" }
Switch              CH_Flame            "Flame"             { channel="atagone:thermostat:boiler:flame" }
Number:Temperature  DHW_Temp            "DHW [%.1f °C]"     { channel="atagone:thermostat:boiler:dhw-temperature" }
Number:Pressure     CH_Water_Pressure   "Pressure [%.2f bar]" { channel="atagone:thermostat:boiler:ch-water-pressure" }
```

# Modbus SMA Binding

This binding reads SMA inverters via the SMA Modbus protocol (Modbus TCP).
It is an extension of the [Modbus Binding](https://www.openhab.org/addons/bindings/modbus/) and comes with it: installing the Modbus Binding makes the SMA things available.

The binding is **read only**.
It uses the SMA specific registers (unit ID 3), not SunSpec, and therefore offers SMA values that SunSpec does not have: device and grid relay status, events, insulation and residual current, battery values and the values of an SMA Energy Meter / Sunny Home Manager as known by the inverter.

## Supported Devices

SMA devices of the **Speedwire platform** share one Modbus register space; they differ in the registers they offer.
The binding detects the available channels itself (see [Channel Detection](#channel-detection)), so no model specific configuration is needed.

Tested:

- Sunny Tripower 10.0 SE (hybrid inverter with battery and Sunny Home Manager)
- Sunny Tripower 10.0-3AV-40

According to the SMA register lists the following devices use the same registers and are expected to work:
Sunny Tripower 3.0–10.0 (-3AV-40), Sunny Tripower 5.0–10.0 SE, Sunny Tripower CORE1, Sunny Tripower TL-30, Sunny Highpower PEAK3, Sunny Boy 3.0–6.0 (-1AV-41, -1VL-40), Sunny Boy Storage 2.5 and 3.7–6.0.
Reports about further devices are welcome.

Not supported:

- devices of the ennexOS platform (e.g. Sunny Tripower X, Sunny Tripower 80, Data Manager): same register numbers, but different unit IDs per device
- older devices without Modbus interface

## Supported Things

| Thing type | Device class | Example |
|---|---|---|
| `sma-inverter` | Solar inverter | Sunny Tripower -3AV-40, CORE1, Sunny Boy |
| `sma-hybrid-inverter` | Hybrid inverter | Sunny Tripower SE |
| `sma-battery-inverter` | Battery inverter | Sunny Boy Storage |

The thing types differ only in the channel groups they offer.
If the device class reported by the device does not match the thing type, a warning in the log names the fitting thing type.

## Preparation

1. Enable the Modbus server of the inverter: in the inverter's web interface (installer login) under _Device Parameters_ → _External Communication_ → _Modbus_ → _TCP Server_ set _Activated_ to _Yes_.
   The menu names may differ with the firmware version; the default port is 502.
1. Create a Modbus TCP bridge (`tcp`) with the IP address of the inverter, port 502 and **id 3** (the SMA unit ID; unit ID 126 is SunSpec and does not work with this binding).
1. Add one of the SMA things to the bridge.

An SMA inverter accepts at most 4 Modbus TCP connections.
Every bridge to the same inverter uses one of them.

## Thing Configuration

| Parameter | Default | Description |
|---|---|---|
| `refresh` | 15 | Poll interval in seconds for power, current, voltage and status values. SMA recommends at least 10 s. |
| `slowRefresh` | 60 | Poll interval in seconds for counters, temperatures and diagnostic values. |
| `maxTries` | 3 | Maximum tries per read request (advanced). |
| `mppTrackers` | 0 | Number of DC inputs (groups `dc-a` …). 0 detects them automatically (advanced). |

## Channel Detection

An SMA device returns "not a number" (NaN) for registers it does not support, but it does the same for many registers at night.
Therefore the binding decides at initialization which channels to create:

- Values that are valid around the clock if supported (status, counters, battery) are decided immediately.
- Values that are only valid while the inverter feeds in (AC and DC values, insulation, residual current) are decided while the inverter feeds in.
  If the thing is initialized at night, these channels are created and decided at the first poll in operation; unsupported ones are removed then.
- The groups `battery` and `meter` are removed completely if the device has no battery or no energy meter data.
- If a register has an old and a new address (e.g. reactive power 31497 / 30805), the one returning a value is used.

The result is logged at level INFO.
To detect the channels again, e.g. after adding a battery, disable and enable the thing.

## Properties

| Property | Description |
|---|---|
| `vendor` | Manufacturer |
| `modelId` | SMA model designation, e.g. `STP10.0-3SE-40` |
| `serialNumber` | Serial number |
| `firmwareVersion` | Software package, e.g. `4.8.39.R` |
| `deviceClass` | SMA device class |

## Channels

Values the device reports as "not available" are handled per channel, as given in the column _When not available_:
power and current become `0` (nothing flows), measurement values become `UNDEF`, counters keep their last value.

<!-- BEGIN generated channel tables (sma-modbus-dev/scripts/generate.py) -->

### Group `status`

Device status. Status codes are numbers with state options (labels as in the SMA documentation).

| Channel | Type | Register | Description | When not available |
|---|---|---|---|---|
| `health` | Number | 30201 | Device Health (status code) | UNDEF |
| `grid-relay` | Number | 30217 | Grid Relay (status code) | UNDEF |
| `operating-status` | Number | 40029 | Operating Status (status code) | UNDEF |
| `run-status` | Number | 33003 | Run Status (status code) | UNDEF |
| `derating-reason` | Number | 30219 | Derating Reason (status code) | UNDEF |
| `recommended-action` | Number | 30211 | Recommended Action (status code) | UNDEF |
| `event-message` | Number | 30213 | Event Message (status code) | UNDEF |
| `event-number` | Number | 30247 | Event Number | UNDEF |
| `grid-connection` | Number | 30881 | Plant Grid Connection (status code) | UNDEF |

### Group `ac`

Grid side of the inverter.

| Channel | Type | Register | Description | When not available |
|---|---|---|---|---|
| `active-power` | Number:Power | 30775 | Active Power | 0 |
| `active-power-l1` | Number:Power | 30777 | Active Power L1 | 0 |
| `active-power-l2` | Number:Power | 30779 | Active Power L2 | 0 |
| `active-power-l3` | Number:Power | 30781 | Active Power L3 | 0 |
| `reactive-power` | Number:Power | 31497 → 30805 | Reactive Power | 0 |
| `apparent-power` | Number:Power | 30813 | Apparent Power | 0 |
| `power-factor` | Number | 30949 | Power Factor | UNDEF |
| `current` | Number:ElectricCurrent | 30795 | Grid Current | 0 |
| `current-l1` | Number:ElectricCurrent | 30977 | Grid Current L1 | 0 |
| `current-l2` | Number:ElectricCurrent | 30979 | Grid Current L2 | 0 |
| `current-l3` | Number:ElectricCurrent | 30981 | Grid Current L3 | 0 |
| `voltage-l1` | Number:ElectricPotential | 30783 | Grid Voltage L1 | UNDEF |
| `voltage-l2` | Number:ElectricPotential | 30785 | Grid Voltage L2 | UNDEF |
| `voltage-l3` | Number:ElectricPotential | 30787 | Grid Voltage L3 | UNDEF |
| `voltage-l1-l2` | Number:ElectricPotential | 30789 | Grid Voltage L1-L2 | UNDEF |
| `voltage-l2-l3` | Number:ElectricPotential | 30791 | Grid Voltage L2-L3 | UNDEF |
| `voltage-l3-l1` | Number:ElectricPotential | 30793 | Grid Voltage L3-L1 | UNDEF |
| `frequency` | Number:Frequency | 30803 | Grid Frequency | UNDEF |

### Group `dc-a … dc-f`

One group per DC input (MPP tracker): `dc-a` … `dc-f`. Groups the device does not have are removed.

| Channel | Type | Register | Description | When not available |
|---|---|---|---|---|
| `current` | Number:ElectricCurrent | 30769 / 30957 / 30963 / 30969 / 31209 / 31215 | DC Current | 0 |
| `voltage` | Number:ElectricPotential | 30771 / 30959 / 30965 / 30971 / 31211 / 31217 | DC Voltage | UNDEF |
| `power` | Number:Power | 30773 / 30961 / 30967 / 30973 / 31213 / 31219 | DC Power | 0 |

### Group `energy`

Yield and operating time counters.

| Channel | Type | Register | Description | When not available |
|---|---|---|---|---|
| `total-yield` | Number:Energy | 30513 → 30529 | Total Yield | last value kept |
| `daily-yield` | Number:Energy | 30517 → 30535 | Daily Yield | last value kept |
| `operating-time` | Number:Time | 30521 | Operating Time | last value kept |
| `feed-in-time` | Number:Time | 30525 | Feed-In Time | last value kept |

### Group `meter`

Values at the grid connection point as known by the inverter. Only available with an SMA Energy Meter or Sunny Home Manager.

| Channel | Type | Register | Description | When not available |
|---|---|---|---|---|
| `grid-draw-power` | Number:Power | 30865 | Grid Draw Power | UNDEF |
| `grid-feed-in-power` | Number:Power | 30867 | Grid Feed-In Power | UNDEF |
| `grid-draw-energy` | Number:Energy | 30581 | Grid Draw Energy | last value kept |
| `grid-feed-in-energy` | Number:Energy | 30583 | Grid Feed-In Energy | last value kept |

### Group `battery`

Battery values (`sma-hybrid-inverter`, `sma-battery-inverter`).

| Channel | Type | Register | Description | When not available |
|---|---|---|---|---|
| `state-of-charge` | Number:Dimensionless | 30845 | Battery State of Charge | UNDEF |
| `current` | Number:ElectricCurrent | 30843 | Battery Current | 0 |
| `voltage` | Number:ElectricPotential | 30851 | Battery Voltage | UNDEF |
| `charge-power` | Number:Power | 31393 | Battery Charge Power | 0 |
| `discharge-power` | Number:Power | 31395 | Battery Discharge Power | 0 |
| `operating-status` | Number | 30955 | Battery Operating Status (status code) | UNDEF |
| `capacity` | Number:Dimensionless | 30847 | Battery Capacity | UNDEF |
| `rated-capacity` | Number:Energy | 40187 | Battery Rated Capacity | UNDEF |
| `temperature` | Number:Temperature | 30849 | Battery Temperature | UNDEF |
| `health` | Number | 31391 | Battery Health (status code) | UNDEF |
| `usage-area` | Number | 31057 | Battery Usage Area (status code) | UNDEF |
| `charged-energy` | Number:Energy | 31397 | Battery Charged Energy | last value kept |
| `discharged-energy` | Number:Energy | 31401 | Battery Discharged Energy | last value kept |

### Group `diagnostics`

Insulation, residual current, temperature and event counters.

| Channel | Type | Register | Description | When not available |
|---|---|---|---|---|
| `residual-current` | Number:ElectricCurrent | 31247 | Residual Current | UNDEF |
| `insulation-resistance` | Number:ElectricResistance | 30225 | Insulation Resistance | UNDEF |
| `internal-temperature` | Number:Temperature | 30953 → 34113 | Internal Temperature | UNDEF |
| `grid-connections` | Number | 30599 | Grid Connections | last value kept |
| `event-count-user` | Number | 30559 | Events for User | last value kept |
| `event-count-installer` | Number | 30561 | Events for Installer | last value kept |
| `event-count-service` | Number | 30563 | Events for Service | last value kept |
<!-- END generated channel tables -->

## Full Example

### Thing Configuration

```java
Bridge modbus:tcp:sma "SMA Inverter Modbus" [ host="192.168.1.50", port=502, id=3 ] {
    Thing sma-hybrid-inverter inverter "SMA Sunny Tripower SE" [ refresh=15, slowRefresh=60 ]
}
```

### Item Configuration

```java
Number:Power             SMA_AC_Power        "AC Power [%.0f W]"             { channel="modbus:sma-hybrid-inverter:sma:inverter:ac#active-power" }
Number:Power             SMA_Grid_Draw       "Grid Draw [%.0f W]"            { channel="modbus:sma-hybrid-inverter:sma:inverter:meter#grid-draw-power" }
Number:Power             SMA_Grid_Feed_In    "Grid Feed-In [%.0f W]"         { channel="modbus:sma-hybrid-inverter:sma:inverter:meter#grid-feed-in-power" }
Number:Dimensionless     SMA_Battery_SoC     "Battery [%.0f %%]"             { channel="modbus:sma-hybrid-inverter:sma:inverter:battery#state-of-charge", unit="%" }
Number:Energy            SMA_Daily_Yield     "Daily Yield [%.2f kWh]"        { channel="modbus:sma-hybrid-inverter:sma:inverter:energy#daily-yield" }
Number                   SMA_Health          "Health [%s]"                   { channel="modbus:sma-hybrid-inverter:sma:inverter:status#health" }
```

AC power, grid draw and grid feed-in are always read in one Modbus request, so they form a consistent snapshot, e.g. for a house consumption calculation.

## Troubleshooting

| Thing status | Meaning |
|---|---|
| `OFFLINE (COMMUNICATION_ERROR)` | The inverter cannot be reached or does not answer. The binding retries after `slowRefresh` seconds. |
| `OFFLINE (CONFIGURATION_ERROR)` | Invalid thing configuration. |

For details enable debug logging for `org.openhab.binding.modbus.sma` (and `org.openhab.core.io.transport.modbus` for the Modbus communication).

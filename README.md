# How to use
## Requirements
- Tailscale installed and activated on both the host and client device

## Instructions
### Client Setup
- Install the App on both the client and host device.
- Move to the "Settings" tab on the host device.
- Enter the IP-Address of the client device from your tailscale network.
- Everything should be set up, now you can start adding projects and modules. To test this, check the connectivity by moving to the WebUI tab.

### Peripheral Setup
- To set up a peripheral for use as a switch, sensor, or a mix of both, please refer to the chapter for data transfer standards to see the JSON structure which needs to be sent over bluetooth so the app recognizes the data sent by the device. 


## Example
opt.

# What it is
OSSI enables you to turn your old phone into a remote controlled Hub for your personal use.
Use cases include remote controlling multiple switches (Like for switching a server on and off), a dashboard provider for gathering live data from multiple sensors at once, or both!
You can also use your client phone as a sensor bundle, gathering the sensor data from any of the sensors installed on your phone. These will be automatically displayed in the WebUI tab.

# How it works


## Client Host Structure


## Project Structure

Projects represent the top most level of any OSSI project. A project contains multiple (or a single) module, a module contains multiple (or a single) peripheral.

Projects -> Modules -> Peripherals

## Peripherals and Data Transfer over bluetooth
Peripherals represent the lowest level of a project. For example, every sensor or switch is a peripheral.
Peripherals can be connected to the client device via Bluetooth (using an ESP32 or ESP8266, or really any microcontroller that supports bluetooth).

The data sent needs to respect a specific data format to be accepted by the client device:

to be added



# Example

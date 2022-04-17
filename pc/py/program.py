#!/usr/bin/env python3

#sudo pip install pyusb

import time
from xml.etree.ElementTree import tostring
import usb.core
import usb.util

#指定されたデバイスを開き、アクセサリーモードに遷移させるコマンドを発行
def enter_accessory_mode(device):
    data = device.ctrl_transfer(0xC0, 51, 0, 0, 2)
    version = data[0] | data[1]<<8
    print(version)

    if(version > 0):
            print("change request")

            manufacturer_name = "THIS IS MANUFACTURER NAME\0"
            model_name = "THIS IS MODEL NAME\0"
            description = "THIS IS DESCRIPTION\0"
            version = "THIS IS VERSION\0"
            URI = "http://example.com/\0"
            serial_number = "THIS IS SERIAL NUMBER\0"

            device.ctrl_transfer(0x40, 52, 0, 0, manufacturer_name, len(manufacturer_name) )
            device.ctrl_transfer(0x40, 52, 0, 1, model_name, len(model_name) )
            device.ctrl_transfer(0x40, 52, 0, 2, description, len(description) )
            device.ctrl_transfer(0x40, 52, 0, 3, version, len(version) )
            device.ctrl_transfer(0x40, 52, 0, 4, URI, len(URI) )
            device.ctrl_transfer(0x40, 52, 0, 5, serial_number, len(serial_number) )

            device.ctrl_transfer(0x40, 53, 0, 0, 0)
            usb.util.dispose_resources(device)
            device = None
            time.sleep(2)
    return

def try_open_accessory_mode():
    #見つけたデバイスを開く
    isAoa = False
    device = usb.core.find(idVendor=0x18D1, idProduct=0x2D00)
    if device is None:
        device = usb.core.find(idVendor=0x18D1, idProduct=0x2D01)
        if device is None:
            raise ValueError("Device not found 0x18D1 0x2D00 or 0x2D01")

    print("Device AoA found")
    return device

def open_accessory_device():
    # デバイスを探す
    device = usb.core.find(idVendor=0x18D1)
    if device is None:
        raise ValueError("Device not found 0x18D1")
    print("Device found 0x18D1")
    print(device.idProduct)

    if (device.idProduct == 0x2D00) or (device.idProduct == 0x2D01):
        print("AoA")
    else:
        print("Not Aoa")
        enter_accessory_mode(device)
    usb.util.dispose_resources(device)

    return try_open_accessory_mode()

def communication(device):
    c=0
    while True:
        time.sleep(0.01)
        c+=1
        try:
            msg = "hello"+str(c)
            print("OUT> "+msg)
            device.write(0x01, msg, 50)
        except usb.core.USBTimeoutError as e:
            pass
        except usb.core.USBError as e:
            print(e)
            break

        try:
            data = device.read(0x81,16384,50)
            msg = ""
            for i in range(len(data)):
                msg += chr(data[i])

            print("IN>" + msg)
        except usb.core.USBTimeoutError as e:
            pass
        except usb.core.USBError as e:
            print(e)
            break

def main():
    while True:
        while True:
            device = None
            try:
                device = open_accessory_device()
            except Exception as e:
                print(e)
            if(device is not None):
                break
            time.sleep(1)

        communication(device)

if __name__ == '__main__':
    main()
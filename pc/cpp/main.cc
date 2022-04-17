/**
 * SegsAoASampleProject by gpsnmeajp v0.01
 * 
 * These codes are licensed under CC0.
 * http://creativecommons.org/publicdomain/zero/1.0/deed.ja
 */
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <libusb.h>

const int GOOGLE_VENDORID = 0x18D1;

libusb_context *ctx = nullptr;

//指定されたデバイスを開き、アクセサリーモードに遷移させるコマンドを発行
bool EnterAccessoryMode(int venderid, int productid)
{
    printf("+ EnterAccessoryMode()\n");
    bool status = false;
    //通常のデバイスとして開く。
    libusb_device_handle *handle = libusb_open_device_with_vid_pid(ctx, venderid, productid);
    printf("libusb_open_device_with_vid_pid(ctx, %04X, %04X) = %p\n", venderid, productid, handle);
    if (handle == nullptr)
    {
        printf("Fail !\n");
        return status;
    }

    do
    {
        //使用権要求
        int claim = libusb_claim_interface(handle, 0);
        printf("libusb_claim_interface = %d\n", claim);
        if (claim != 0)
        {
            //失敗
            printf("Fail !\n");
            break;
        }

        //アクセサリプロトコル対応チェック
        unsigned char data[2] = {0};
        int result = libusb_control_transfer(handle, 0xC0, 51, 0, 0, data, 2, 1000);
        int version = ((int)data[1] << 8 | (int)data[0]);
        printf("libusb_control_transfer(version check) = %d\n", result);
        printf("Version = %d\n", version);

        if (version > 0)
        {
            //バージョンチェック成功
            printf("Version check ok\n");

            //デバイス情報を送信する
            unsigned char manufacturer_name[] = "THIS IS MANUFACTURER NAME";
            unsigned char model_name[] = "THIS IS MODEL NAME";
            unsigned char description[] = "THIS IS DESCRIPTION";
            unsigned char version[] = "THIS IS VERSION";
            unsigned char URI[] = "http://example.com/";
            unsigned char serial_number[] = "THIS IS SERIAL NUMBER";

            libusb_control_transfer(handle, 0x40, 52, 0, 0, manufacturer_name, strlen((char *)manufacturer_name) + 1, 1000);
            libusb_control_transfer(handle, 0x40, 52, 0, 1, model_name, strlen((char *)model_name) + 1, 1000);
            libusb_control_transfer(handle, 0x40, 52, 0, 2, description, strlen((char *)description) + 1, 1000);
            libusb_control_transfer(handle, 0x40, 52, 0, 3, version, strlen((char *)version) + 1, 1000);
            libusb_control_transfer(handle, 0x40, 52, 0, 4, URI, strlen((char *)URI) + 1, 1000);
            libusb_control_transfer(handle, 0x40, 52, 0, 5, serial_number, strlen((char *)serial_number) + 1, 1000);
            printf("Accessory info send\n");

            //アクセサリ切り替えリクエストを送信する
            libusb_control_transfer(handle, 0x40, 53, 0, 0, nullptr, 0, 1000);
            printf("Accessory on request\n");

            //成功
            status = true;
        }
        //通信切る
        libusb_release_interface(handle, 0);
        printf("libusb_release_interface\n");
    } while (false);

    libusb_close(handle);
    printf("libusb_close\n");
    return status;
}

//アクセサリーを探索して開く
libusb_device_handle *TryOpenAccessoryMode()
{
    printf("+ TryOpenAccessoryMode\n");
    libusb_device_handle *handle = nullptr;

    handle = libusb_open_device_with_vid_pid(ctx, GOOGLE_VENDORID, 0x2D00);
    printf("libusb_open_device_with_vid_pid(ctx, %04X, %04X) = %p\n", GOOGLE_VENDORID, 0x2D00, handle);
    if (handle != nullptr)
    {
        int claim = libusb_claim_interface(handle, 0);
        printf("libusb_claim_interface = %d\n", claim);
        if (claim != 0)
        {
            return nullptr;
        }
        return handle;
    }

    handle = libusb_open_device_with_vid_pid(ctx, GOOGLE_VENDORID, 0x2D01);
    printf("libusb_open_device_with_vid_pid(ctx, %04X, %04X) = %p\n", GOOGLE_VENDORID, 0x2D01, handle);
    if (handle != nullptr)
    {
        int claim = libusb_claim_interface(handle, 0);
        printf("libusb_claim_interface = %d\n", claim);
        if (claim != 0)
        {
            return nullptr;
        }
        return handle;
    }

    return nullptr;
}

//アクセサリを開く
libusb_device_handle *OpenAccessoryDevice()
{
    printf("+ OpenAccessoryDevice\n");
    libusb_device_handle *handle = nullptr;

    //アクセサリーモードのデバイスがあるかどうかチェック
    handle = TryOpenAccessoryMode();
    if (handle)
    {
        printf("Open !\n");
        return handle;
    }

    //アクセサリーモード以外のデバイスがあるかチェック
    libusb_device **list = nullptr;
    int count = libusb_get_device_list(ctx, &list);
    printf("libusb_get_device_list = %d\n", count);
    for (int i = 0; i < count; i++)
    {
        libusb_device_descriptor descriptor{};
        libusb_device *device = list[i];
        int ret = libusb_get_device_descriptor(device, &descriptor);
        printf("libusb_get_device_descriptor = %d\n", ret);

        //Androidに関係ありそうなものを片っ端から試してみる
        if (descriptor.idVendor == GOOGLE_VENDORID)
        {
            printf("FIND: %04X %04X\n", descriptor.idVendor, descriptor.idProduct);
            if (EnterAccessoryMode(descriptor.idVendor, descriptor.idProduct))
            {
                printf("SUCCESS\n");

                break;
            }
        }
    }
    libusb_free_device_list(list, 0);
    printf("libusb_free_device_list\n");
    return nullptr;
}

void Communication(libusb_device_handle *handle)
{
    int i = 0;
    if (handle == nullptr)
    {
        return;
    }

    while (true)
    {
        //[ データを送信する ]
        char data2[65536]{0};
        snprintf(data2, 65535, "%d", i);
        int s = libusb_bulk_transfer(handle, LIBUSB_ENDPOINT_OUT | 1, (unsigned char *)data2, strlen(data2) + 1, nullptr, 10);
        printf("libusb_bulk_transfer(OUT) = %d\n",s);
        if (s == 0)
        {
            i++;
        }
        printf("OUT > %s\n", data2);

        //[ データを受信する ]
        unsigned char data[16384] = {0};
        int data_len = 0;
        int rcv_ret = libusb_bulk_transfer(handle, LIBUSB_ENDPOINT_IN | 1, data, sizeof(data), &data_len, 10);
        printf("libusb_bulk_transfer(IN) = %d\n",rcv_ret);
        if (rcv_ret == 0)
        {
            printf("IN > ");
            for (int i = 0; i < data_len; i++)
            {
                printf("%c", data[i]);
            }
            printf("\n");
        }
        else if (rcv_ret == LIBUSB_ERROR_TIMEOUT)
        {
            printf("Timeout\n");
            usleep(100 * 1000);
        }
        else
        {
            break;
        }
        //タイムアウト以外のエラーが起きたときbreakする
    }
}

void CloseAccessoryDevice(libusb_device_handle *handle)
{
    //通信切る
    if (handle != nullptr)
    {
        libusb_release_interface(handle, 0);
        printf("libusb_release_interface\n");
        libusb_close(handle);
        printf("libusb_close\n");
    }
}

//メイン処理
int main()
{
    // libusbを初期化
    int ret = libusb_init(&ctx);
    printf("libusb_init = %d\n", ret);
    if (ret != 0)
    {
        return -1;
    }

    ret = libusb_set_option(ctx, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_WARNING);
    printf("libusb_set_option = %d\n", ret);

    //切断時ループ
    while (true)
    {
        //接続
        libusb_device_handle *handle = nullptr;
        do
        {
            sleep(1);
            handle = OpenAccessoryDevice();
            printf("OpenAccessoryDevice = %p\n", handle);
        } while (handle == nullptr);

        //通信ループ
        Communication(handle);

        //通信切断
        CloseAccessoryDevice(handle);
    }

    // libusbを停止
    if (ctx != nullptr)
    {
        libusb_exit(ctx);
        printf("libusb_exit\n");

        ctx = nullptr;
    }

    return 0;
}
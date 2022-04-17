# 環境構築
sudo apt install libusb-1.0-0
sudo apt install libusb-1.0-0-dev

# ビルド方法
g++ -o program -I /usr/include/libusb-1.0 main.cc -lusb-1.0

# 実行方法
sudo ./program

# 参考文献
https://zenn.dev/k16/articles/e72ff8bf2a640e
https://libusb.sourceforge.io/api-1.0/group__libusb__asyncio.html#gabb0932601f2c7dad2fee4b27962848ce
https://libusb.sourceforge.io/api-1.0/group__libusb__hotplug.html#ga5ab3955e2110a3099497a66256fb7fab
https://www.slideshare.net/masatakakono1/usb-87470849
https://qiita.com/gpsnmeajp/items/b1282b2d3c14470bbae7
https://developer.android.com/guide/topics/connectivity/usb/accessory?hl=ja
https://poly.hatenablog.com/entry/20110523/p1
http://y-anz-m.blogspot.com/2011/12/androidhello-adk.html
https://qiita.com/kurun_pan/items/f626e763e74e82a44493
https://issuetracker.google.com/issues/36933798
#!/system/bin/sh
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
echo "hello from execute-hello"
mkdir -p /data/nemesis
echo "execute-hello ok" > /data/nemesis/execute-hello.ok
exit 0

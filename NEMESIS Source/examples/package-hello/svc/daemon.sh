#!/system/bin/sh
export PATH=/system/bin:/system/xbin
echo "hello-daemon start $(date)"
while true; do
    sleep 30
done

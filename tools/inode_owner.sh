#!/system/bin/sh
# Usage: inode_owner.sh <inode> [<inode> ...] (run as root)
for ino in "$@"; do
  for p in /proc/[0-9]*; do
    for f in "$p"/fd/*; do
      l=$(readlink "$f" 2>/dev/null)
      if [ "$l" = "socket:[$ino]" ]; then
        pid=${p#/proc/}
        echo "$ino pid=$pid uid=$(stat -c %u "$p") cmd=$(tr '\0' ' ' < "$p/cmdline")"
      fi
    done
  done
done

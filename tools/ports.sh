#!/system/bin/sh
# Owners of tbox-proxy sockets: UDP 11048 (0x2B28), TCP 1104 (0x0450) / 1105 (0x0451).
show() {
  file=$1; port=$2; label=$3
  [ -r "$file" ] || return
  while read -r sl local rem st queues timer retr uid rest; do
    case "$local" in
      *:"$port")
        name=$(cmd package list packages -U 2>/dev/null | grep "uid:$uid\$" | head -1 | sed 's/package://; s/ uid:.*//')
        echo "$label $file st=$st uid=$uid ${name:-?}"
        ;;
    esac
  done < "$file"
}
show /proc/net/udp 2B28 UDP11048
show /proc/net/udp6 2B28 UDP11048
show /proc/net/tcp 0450 TCP1104
show /proc/net/tcp6 0450 TCP1104
show /proc/net/tcp 0451 TCP1105
show /proc/net/tcp6 0451 TCP1105

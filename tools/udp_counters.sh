#!/system/bin/sh
# Counting-only iptables rules for tbox-proxy UDP (sport 11048 → 192.168.225.1:50047).
# Usage (root): udp_counters.sh add | show | del
RULES="
OUTPUT -o usb0 -p udp --sport 11048 -d 192.168.225.1
OUTPUT -o wlan0 -p udp --sport 11048 -d 192.168.225.1
INPUT -i usb0 -p udp --dport 11048 -s 192.168.225.1
"
case "$1" in
  add)
    echo "$RULES" | while read -r chain rest; do
      [ -n "$chain" ] || continue
      iptables -w -C $chain $rest 2>/dev/null || iptables -w -I $chain 1 $rest
    done
    ;;
  del)
    echo "$RULES" | while read -r chain rest; do
      [ -n "$chain" ] || continue
      while iptables -w -D $chain $rest 2>/dev/null; do :; done
    done
    ;;
  show)
    iptables -w -L OUTPUT -n -v -x | grep -E 'spt:11048' | awk '{print "OUT", $1, "pkts via", $7}'
    iptables -w -L INPUT -n -v -x | grep -E 'dpt:11048' | awk '{print "IN ", $1, "pkts via", $6}'
    ;;
esac

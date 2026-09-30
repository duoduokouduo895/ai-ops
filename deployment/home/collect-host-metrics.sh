#!/bin/sh
set -eu

output_dir=${1:-/var/lib/node_exporter/textfile_collector}
output_file="$output_dir/homeops_host.prom"
temporary_file="$output_file.$$"

cleanup() {
  rm -f "$temporary_file"
}
trap cleanup EXIT HUP INT TERM

is_number() {
  printf '%s' "$1" | grep -Eq '^[0-9]+([.][0-9]+)?$'
}

read_value() {
  if [ -r "$1" ]; then
    tr -d '\r\n' < "$1"
  fi
}

emit_gauge() {
  metric=$1
  value=$2
  if is_number "$value"; then
    printf '%s %s\n' "$metric" "$value" >> "$temporary_file"
  fi
}

mkdir -p "$output_dir"
: > "$temporary_file"
ac_online=''
battery_seen=0
cpu_temperature_seen=0
nvme_temperature_seen=0

cat >> "$temporary_file" <<'EOF'
# HELP homeops_collector_last_success_timestamp_seconds Unix timestamp of the latest successful host-side collection.
# TYPE homeops_collector_last_success_timestamp_seconds gauge
# HELP homeops_ac_online Whether an AC mains supply is online (1 online, 0 offline).
# TYPE homeops_ac_online gauge
# HELP homeops_battery_capacity_ratio Current battery state of charge as a ratio from 0 to 1.
# TYPE homeops_battery_capacity_ratio gauge
# HELP homeops_battery_health_ratio Current full-charge energy divided by design energy.
# TYPE homeops_battery_health_ratio gauge
# HELP homeops_battery_power_watts Current battery discharge power. Present only while discharging.
# TYPE homeops_battery_power_watts gauge
# HELP homeops_temperature_celsius Canonical host temperature selected from hwmon.
# TYPE homeops_temperature_celsius gauge
EOF

for supply in /host/sys/class/power_supply/*; do
  [ -d "$supply" ] || continue
  supply_type=$(read_value "$supply/type")
  case "$supply_type" in
    Mains|USB|USB_C)
      online=$(read_value "$supply/online")
      if is_number "$online"; then
        if [ "$online" -ge 1 ]; then ac_online=1; elif [ -z "$ac_online" ]; then ac_online=0; fi
      fi
      ;;
    Battery)
      [ "$battery_seen" -eq 0 ] || continue
      capacity=$(read_value "$supply/capacity")
      is_number "$capacity" || continue
      battery_seen=1
      capacity_ratio=$(awk -v value="$capacity" 'BEGIN { printf "%.6f", value / 100 }')
      emit_gauge homeops_battery_capacity_ratio "$capacity_ratio"

      energy_full=$(read_value "$supply/energy_full")
      energy_design=$(read_value "$supply/energy_full_design")
      if is_number "$energy_full" && is_number "$energy_design" && [ "$energy_design" -gt 0 ]; then
        health_ratio=$(awk -v full="$energy_full" -v design="$energy_design" 'BEGIN { printf "%.6f", full / design }')
        emit_gauge homeops_battery_health_ratio "$health_ratio"
      fi

      status=$(read_value "$supply/status")
      power_now=$(read_value "$supply/power_now")
      if [ "$status" = "Discharging" ] && is_number "$power_now" && [ "$power_now" -gt 0 ]; then
        power_watts=$(awk -v value="$power_now" 'BEGIN { printf "%.6f", value / 1000000 }')
        emit_gauge homeops_battery_power_watts "$power_watts"
      fi
      ;;
  esac
done

if [ -n "$ac_online" ]; then
  emit_gauge homeops_ac_online "$ac_online"
fi

for hwmon in /host/sys/class/hwmon/hwmon*; do
  [ -d "$hwmon" ] || continue
  chip_name=$(read_value "$hwmon/name")
  sensor=''
  case "$chip_name" in
    k10temp)
      [ "$cpu_temperature_seen" -eq 0 ] || continue
      sensor=cpu
      ;;
    nvme)
      [ "$nvme_temperature_seen" -eq 0 ] || continue
      sensor=nvme
      ;;
  esac
  [ -n "$sensor" ] || continue
  temperature=$(read_value "$hwmon/temp1_input")
  if is_number "$temperature"; then
    celsius=$(awk -v value="$temperature" 'BEGIN { printf "%.3f", value / 1000 }')
    emit_gauge "homeops_temperature_celsius{sensor=\"$sensor\"}" "$celsius"
    if [ "$sensor" = cpu ]; then cpu_temperature_seen=1; else nvme_temperature_seen=1; fi
  fi
done

emit_gauge homeops_collector_last_success_timestamp_seconds "$(date +%s)"
mv -f "$temporary_file" "$output_file"
trap - EXIT HUP INT TERM

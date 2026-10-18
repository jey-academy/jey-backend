#!/bin/bash
# NAT 인스턴스(Ubuntu 26.04 LTS) 첫 부팅 설정.
# 사설 서브넷(10.0.10.0/23)에서 나가는 연결만 이 인스턴스의 주소로 바꿔 내보낸다.
# 설정은 파일로 남겨 재부팅 뒤에도 유지된다.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y nftables

cat > /etc/sysctl.d/90-nat.conf <<'EOF'
net.ipv4.ip_forward = 1
EOF
sysctl --system

IFACE=$(ip route show default | awk '{print $5; exit}')

cat > /etc/nftables.conf <<EOF
#!/usr/sbin/nft -f
flush ruleset
table ip nat {
	chain postrouting {
		type nat hook postrouting priority srcnat; policy accept;
		ip saddr 10.0.10.0/23 oifname "${IFACE}" masquerade
	}
}
EOF

systemctl enable nftables
systemctl restart nftables

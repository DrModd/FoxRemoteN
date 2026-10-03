#!/bin/sh
# install.sh — установка управления усилителем DigiD D1 на PureFox
#   sh /tmp/install.sh          установить (файлы лежат рядом в /tmp)
#   sh /tmp/install.sh remove   убрать всё и вернуть консоль как было
WWW=/var/www
SRC=$(dirname "$0")

if [ "$1" = "remove" ]; then
    [ -f /etc/inittab.orig ] && cp /etc/inittab.orig /etc/inittab
    sed -i '/pfctl serve/d' /etc/inittab
    rm -f /usr/bin/pfctl $WWW/amp.php $WWW/rate.php /tmp/amp_state /tmp/amp_req
    echo "Удалено. Перезагрузите Фокс: reboot"
    exit 0
fi

for f in pfctl amp.php rate.php; do
    [ -f "$SRC/$f" ] || { echo "ОШИБКА: нет файла $SRC/$f — скопируйте его в /tmp"; exit 1; }
done
[ -f $WWW/volume.php ] || { echo "ОШИБКА: веб-интерфейс PureFox не найден в $WWW"; exit 1; }

# убрать CR (если файлы правились в Windows)
for f in pfctl amp.php rate.php; do sed -i 's/\r$//' "$SRC/$f"; done

cp "$SRC/pfctl" /usr/bin/pfctl && chmod 755 /usr/bin/pfctl
cp "$SRC/amp.php" "$SRC/rate.php" $WWW/ && chmod 644 $WWW/amp.php $WWW/rate.php

# консоль ttyFIQ0: вместо входа в систему (getty) — сервер pfctl
[ -f /etc/inittab.orig ] || cp /etc/inittab /etc/inittab.orig
# (getty, login или -/bin/sh на ttyFIQ0 / console комментируются — иначе они
#  будут читать UART вместе с pfctl)
sed -i -e '/pfctl/b' -e '/^#/b' -e '/ttyFIQ0/s/^/#/' -e '/^console:/s/^/#/' -e '/^#/b' -e '/getty.*console/s/^/#/' /etc/inittab
grep -q 'pfctl serve' /etc/inittab || echo 'ttyFIQ0::respawn:/usr/bin/pfctl serve' >> /etc/inittab
sync

echo "---- /etc/inittab (строки консоли) ----"
grep -n 'ttyFIQ0\|^#*console' /etc/inittab
echo "---- проверка ----"
echo "pfctl rate: $(/usr/bin/pfctl rate)"
echo "pfctl ping: $(/usr/bin/pfctl ping)"
echo "Готово. Теперь: reboot"

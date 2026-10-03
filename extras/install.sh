#!/bin/sh
# install.sh — установка управления усилителем DigiD D1 на PureFox
#   sh /tmp/install.sh          установить (файлы лежат рядом в /tmp)
#   sh /tmp/install.sh remove   убрать всё и вернуть как было
WWW=/var/www
SRC=$(dirname "$0")
RC=/etc/rc.pure
FILES="pfctl pfmeta amp.php rate.php track.php"

# сделать копию файла один раз (для remove)
keep() { [ -f "$1" ] && [ ! -f "$1.orig" ] && cp "$1" "$1.orig"; }
back() { [ -f "$1.orig" ] && mv "$1.orig" "$1"; }

if [ "$1" = "remove" ]; then
    back /etc/inittab; sed -i '/pfctl serve/d' /etc/inittab
    back $RC/S95qobuz; back $RC/S95spotify; back /etc/shairport-sync.conf
    [ -f /tmp/pfmeta_airplay.pid ] && kill "$(cat /tmp/pfmeta_airplay.pid)" 2>/dev/null
    rm -f /usr/bin/pfctl /usr/bin/pfmeta /usr/bin/pfmeta_spotify \
          $WWW/amp.php $WWW/rate.php $WWW/track.php /tmp/amp_state /tmp/amp_req /tmp/nowplaying
    echo "Удалено. Перезагрузите Фокс: reboot"
    exit 0
fi

for f in $FILES; do
    [ -f "$SRC/$f" ] || { echo "ОШИБКА: нет файла $SRC/$f — скопируйте его в /tmp"; exit 1; }
done
[ -f $WWW/volume.php ] || { echo "ОШИБКА: веб-интерфейс PureFox не найден в $WWW"; exit 1; }

# убрать CR (если файлы правились в Windows)
for f in $FILES; do sed -i 's/\r$//' "$SRC/$f"; done

cp "$SRC/pfctl" "$SRC/pfmeta" /usr/bin/ && chmod 755 /usr/bin/pfctl /usr/bin/pfmeta
printf '#!/bin/sh\nexec /usr/bin/pfmeta spotify\n' > /usr/bin/pfmeta_spotify && chmod 755 /usr/bin/pfmeta_spotify
cp "$SRC/amp.php" "$SRC/rate.php" "$SRC/track.php" $WWW/
chmod 644 $WWW/amp.php $WWW/rate.php $WWW/track.php

# ---- консоль ttyFIQ0: вместо входа в систему (getty) — сервер pfctl ----
keep /etc/inittab
# (getty, login или -/bin/sh на ttyFIQ0 / console комментируются — иначе они
#  будут читать UART вместе с pfctl)
sed -i -e '/pfctl/b' -e '/^#/b' -e '/ttyFIQ0/s/^/#/' -e '/^console:/s/^/#/' -e '/^#/b' -e '/getty.*console/s/^/#/' /etc/inittab
grep -q 'pfctl serve' /etc/inittab || echo 'ttyFIQ0::respawn:/usr/bin/pfctl serve' >> /etc/inittab

# ---- названия треков ----
# Qobuz: вывод qobuz-connect (PureFox выбрасывает его) — в pfmeta
if [ -f $RC/S95qobuz ] && ! grep -q pfmeta $RC/S95qobuz; then
    keep $RC/S95qobuz
    sed -i 's#"\$output_device" > /dev/null 2>&1 &#"$output_device" 2>\&1 | /usr/bin/pfmeta qobuz >/dev/null 2>\&1 \&#' $RC/S95qobuz
fi
# Spotify: librespot вызывает pfmeta при смене трека
if [ -f $RC/S95spotify ] && ! grep -q pfmeta $RC/S95spotify; then
    keep $RC/S95spotify
    sed -i 's#--initial-volume 100 \\#--initial-volume 100 --onevent /usr/bin/pfmeta_spotify \\#' $RC/S95spotify
fi
# AirPlay: включить канал метаданных shairport-sync
SP=/etc/shairport-sync.conf
if [ -f $SP ] && ! grep -q '^[[:space:]]*enabled = "yes"; // pfmeta' $SP; then
    keep $SP
    sed -i -e 's#^//[[:space:]]*enabled = "no"; // set this to yes to get Shairport Sync to solicit metadata.*#\tenabled = "yes"; // pfmeta#' \
           -e 's#^//[[:space:]]*pipe_name = "/tmp/shairport-sync-metadata";#\tpipe_name = "/tmp/shairport-sync-metadata";#' $SP
fi
sync

echo "---- /etc/inittab (строки консоли) ----"
grep -n 'ttyFIQ0\|^#*console' /etc/inittab
echo "---- названия треков ----"
grep -c pfmeta $RC/S95qobuz 2>/dev/null | sed 's/^1$/Qobuz:   OK/;s/^0$/Qobuz:   НЕ ИЗМЕНЁН (пришлите S95qobuz)/'
grep -c pfmeta $RC/S95spotify 2>/dev/null | sed 's/^1$/Spotify: OK/;s/^0$/Spotify: НЕ ИЗМЕНЁН (пришлите S95spotify)/'
grep -c '// pfmeta' $SP 2>/dev/null | sed 's/^1$/AirPlay: OK/;s/^0$/AirPlay: НЕ ИЗМЕНЁН (пришлите shairport-sync.conf)/'
echo "---- проверка ----"
echo "pfctl rate: $(/usr/bin/pfctl rate)"
echo "pfctl ping: $(/usr/bin/pfctl ping)"
echo "Готово. Теперь: reboot"

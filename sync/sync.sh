#!/bin/bash
USER="$(cat /settings/USER)"
PASSWORD="$(cat /settings/PASSWORD)"
URL="$(cat /settings/URL)"

cd /data || exit 1
errors=()
export IFS=$'\n'
for album in $(curl https://$USER:$PASSWORD@$URL/rest/albums); do
	id="$( cut -d ':' -f 1 <<<"$album")"
	path="$( cut -d ':' -f 2- <<<"$album")"
	parentdir="$(dirname "$path")"
	albumname="$(basename "$path")"
	[ -d "$parentdir" ] || mkdir -p "$parentdir"
	if [ -d "${path}.git" ]; then
	(
		echo "Fetch $albumname"
		cd "${path}.git"
		git fetch https://$USER:$PASSWORD@$URL/git/$id master:master
	)else(
		echo "Initialize $albumname"
		cd "$parentdir"
		git clone --bare https://$USER:$PASSWORD@$URL/git/$id "${albumname}.git"
	)
	fi || errors+=("${path}.git")
done

if [ ${#errors[@]} -gt 0 ]; then
    echo "---------------------------"
    echo Error found on repositories
    echo "---------------------------"
    for repo in "${errors[@]}"; do
        echo "  $repo"
    done
    echo "---------------------------"
    exit 1
fi

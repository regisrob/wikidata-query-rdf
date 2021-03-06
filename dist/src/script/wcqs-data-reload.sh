#!/bin/bash
set -e

DUMP_FILENAME=latest-mediainfo.ttl.gz
DATA_DIR=$(grep 'data_dir:' /etc/wcqs/vars.yaml | awk '{print $2}')
DEPLOY_DIR=$(grep 'package_dir:' /etc/wcqs/vars.yaml | awk '{print $2}')
MUNGED_DIR=${DATA_DIR}/munged
mkdir -p "${MUNGED_DIR}"

# do not delete the data for now - see https://phabricator.wikimedia.org/T251515#6393397
# trap 'rm -rf ${DATA_DIR}/${DUMP_FILENAME} ${DATA_DIR}/munged' EXIT

echo "Download latest dump"
curl -o ${DATA_DIR}/${DUMP_FILENAME} \
https://dumps.wikimedia.org/other/wikibase/commonswiki/${DUMP_FILENAME}

echo "Running munge script"
${DEPLOY_DIR}/munge.sh \
-f ${DATA_DIR}/${DUMP_FILENAME} \
-c 50000 \
-d ${DATA_DIR}/munged \
-- \
--wikibaseHost commons.wikimedia.org \
--conceptUri http://www.wikidata.org \
--commonsUri https://commons.wikimedia.org

# added for debugging purposes - see https://phabricator.wikimedia.org/T251515#6393397
echo "Munged dir - ${MUNGED_DIR}"

echo "Switching frontend to data reload page"
touch /var/lib/nginx/wdqs/data-reload

echo "Stopping Blazegraph"
sudo systemctl stop wcqs-blazegraph

echo "Deleting blazegraph journal"
rm -rf ${DATA_DIR}/wcqs.jnl

echo "Starting Blazegraph"
sudo systemctl start wcqs-blazegraph

echo "Waiting for Blazegraph to restart"
sleep 30

echo "Loading data"
${DEPLOY_DIR}/loadData.sh -n wcq -h http://localhost:9999 -d "${MUNGED_DIR}"

echo "Switching frontend to standard page"
rm /var/lib/nginx/wdqs/data-reload

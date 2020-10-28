#!/bin/sh

# Splice Machine-customized /opt/conf/mapr-hbase-config.sh

MAPR_HOME="${MAPR_HOME:-/opt/mapr}"
HBASE_VER=hbase1.1.13
HBASE_HOME="${MAPR_HOME}/hbase/${HBASE_VER}-splice"

EXTRA_JARS="gateway-*.jar"
for jar in ${EXTRA_JARS} ; do
  JARS=`echo $(ls ${MAPR_HOME}/lib/${jar} 2> /dev/null) | sed 's/\s\+/:/g'`
  if [ "${JARS}" != "" ]; then
    HBASE_MAPR_EXTRA_JARS=${HBASE_MAPR_EXTRA_JARS}:${JARS}
  fi
done

# Remove any additional ':' from the tail
HBASE_MAPR_EXTRA_JARS="${HBASE_MAPR_EXTRA_JARS#:}"

export HBASE_MAPR_EXTRA_JARS

# Need to call this to continue the daisy chain of config scripts
if [ -f "${HBASE_HOME}/bin/mapr-config.sh" ] ; then
    . "${HBASE_HOME}/bin/mapr-config.sh"
fi

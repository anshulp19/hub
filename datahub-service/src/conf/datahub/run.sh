#!/bin/bash

# Exit on any error
#
set -e

DEPLOYED_DIR=/home/ubuntu/local/datahub-service-0.1.7
JAVA_HOME=/home/ubuntu/local/java
MAX_HEAP=450m
MIN_HEAP=256m
MIN_NEW=100m
DEBUG_PORT=3333
JMXREMOTE_PORT=8888

JAVA_OPTS="
 $JAVA_OPTS
 -d64
 -server
 -Xmx$MAX_HEAP
 -Xms$MIN_HEAP
 -XX:NewSize=$MIN_NEW
 -agentlib:jdwp=transport=dt_socket,address=$DEBUG_PORT,server=y,suspend=n"

# add this for jmxremote/jconsole access
JAVA_OPTS="$JAVA_OPTS
 -Dcom.sun.management.jmxremote
 -Dcom.sun.management.jmxremote.port=$JMXREMOTE_PORT
 -Dcom.sun.management.jmxremote.ssl=false
 -Dcom.sun.management.jmxremote.authenticate=false"


# add this for verbose gc
JAVA_OPTS="$JAVA_OPTS
 -XX:+PrintGCDetails
 -XX:+PrintTenuringDistribution
 -XX:+PrintGCDateStamps"

# disable JVM's stupid DNS cache
JAVA_OPTS="$JAVA_OPTS
 -Dsun.net.inetaddr.ttl=0"


# gc logging
JAVA_OPTS="$JAVA_OPTS
 -Xloggc:/home/ubuntu/local/log/gc.log-$(date -u '+%Y-%m-%d-%H-%M-%S')"

CHILDPID=""
function shutdownChild()
{
        echo signal trap $1 caught - taking stack trace and sleeping 5s before relaying signal

        if [ -z "$CHILDPID" ]
        then
                echo no datahub child PID to shutdown
                return
        fi

        kill -QUIT $CHILDPID
        sleep 5

        kill -TERM $CHILDPID
        echo signal sent, waiting ...
        wait $CHILDPID
        echo datahub is down
        exit
}
trap "shutdownChild TERM" SIGTERM
trap "shutdownChild INT"  SIGINT

export JAVA_OPTS="$JAVA_OPTS"
export JAVA_HOME="$JAVA_HOME"

MAIN_CLASS="com.flightstats.datahub.app.DataHubMain"

if [ -e ${DEPLOYED_DIR}/conf/datahub.properties ] ; then
    DATAHUB_PROPS=${DEPLOYED_DIR}/conf/datahub.properties
fi

echo Running datahub in the background...
nohup ${JAVA_HOME}/bin/java -cp "${DEPLOYED_DIR}/lib/*" ${JAVA_OPTS} ${MAIN_CLASS} ${DATAHUB_PROPS} &
CHILDPID=$!
echo ... datahub pid is $CHILDPID

#!/bin/bash

set -ex

#set the tag from github to grab
buildNum=
pkg=SPLICEMACHINE-${buildNum}.standalone.tar.gz
dir="$(pwd)"
cd ${dir}

#check if directory exists, else clone it
if [ ! -d "${dir}/spliceengine" ]; then
   git clone https://github.com/splicemachine/spliceengine.git
fi

#enter the directory and checkout the build
cd ${dir}/spliceengine
git fetch
git checkout ${buildNum}

#make the target jars needed for standalone
if [ ${buildNum:0:1} == "2" ]; then
   mvn install -Denv=cdh5.16.1 -Pcore,cdh5.16.1,standalone -DskipTests -Dmaven.artifact.threads=30 -Dhbase.version=1.2.0-cdh5.16.1 -DenvClassifier=cdh5.16.1
   ./start-splice-cluster -p cdh5.16.1 -l
fi
if [ ${buildNum:0:1} == "3" ]; then
    mvn install -Denv=cdh6.3.0 -Pcore,cdh6.3.0,standalone -DskipTests -Dmaven.artifact.threads=30 -Dhbase.version=2.1.0-cdh6.3.0 -DenvClassifier=cdh6.3.0
fi
cd ..

#make the standalone directory and copy dependencies into them
mkdir -p ./tarball
mkdir -p ./tarball/splicemachine
mkdir -p ./tarball/splicemachine/lib
mkdir -p ./tarball/splicemachine/jdbc-driver
dest=${dir}/tarball/splicemachine
cp -r ${dir}/spliceengine/assembly/standalone/template/* ${dest}
find ${dir}/spliceengine/platform_it/target/dependency -type f -iname '*.jar' -exec cp {} ${dest}/lib \;
find ${dir}/spliceengine/assembly/target/dependency -type f -iname '*.jar' -exec cp {} ${dest}/lib \;
find ${dir}/spliceengine/platform_it/target/ -type f -iname '*.jar' -not -iname '*sources*' -exec cp {} ${dest}/lib \;
if [ ${buildNum:0:1} == "3" ]; then
    find ${dir}/spliceengine/standalone/target/ -type f -iname '*.jar' -not -iname '*sources*' -exec cp {} ${dest}/lib \;
fi
cp ${dest}/lib/db-client* ${dest}/jdbc-driver

cd tarball
tar zcvf ../${pkg} splicemachine
tar xvf ../${pkg}
rm -rf ${dir}/spliceengine
rm -rf ${dir}/tarball
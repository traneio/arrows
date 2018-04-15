    #!/usr/bin/env bash

set -e # Any subsequent(*) commands which fail will cause the shell script to exit immediately

SBT_CMD="sbt +clean +coverage +test coverageReport coverageAggregate tut checkUnformattedFiles"   
SBT_PUBLISH=" +coverageOff +publish"

if [[ $TRAVIS_PULL_REQUEST == "false" ]]
then
    SBT_CMD+=$SBT_PUBLISH
    openssl aes-256-cbc -pass pass:$ENCRYPTION_PASSWORD -in ./build/secring.gpg.enc -out local.secring.gpg -d
    openssl aes-256-cbc -pass pass:$ENCRYPTION_PASSWORD -in ./build/pubring.gpg.enc -out local.pubring.gpg -d
    openssl aes-256-cbc -pass pass:$ENCRYPTION_PASSWORD -in ./build/credentials.sbt.enc -out local.credentials.sbt -d
    openssl aes-256-cbc -pass pass:$ENCRYPTION_PASSWORD -in ./build/deploy_key.pem.enc -out local.deploy_key.pem -d

    if [[ $TRAVIS_BRANCH == "master" && $(cat version.sbt) != *"SNAPSHOT"* ]]
    then
        # eval "$(ssh-agent -s)"
        # chmod 600 local.deploy_key.pem
        # ssh-add local.deploy_key.pem
        # git config --global user.name "Quill CI"
        # git config --global user.email "quillci@getquill.io"
        # git remote set-url origin git@github.com:traneio/arrows.git
        # git checkout master || git checkout -b master
        # git reset --hard origin/master
        # git push --delete origin website || true

        echo 'Performing a release'
        sbt -Djava.specification.version=9.1 'release cross with-defaults'
    elif [[ $TRAVIS_BRANCH == "master" ]]
    then
        echo 'Master build'
        $SBT_CMD
    else
        echo 'Branch build'
        echo "version in ThisBuild := \"$TRAVIS_BRANCH-SNAPSHOT\"" > version.sbt
        $SBT_CMD
    fi
else
    echo 'PR build'
    $SBT_CMD
fi

#!/usr/bin/env groovy

node('rhel7') {
	stage('Checkout SCM') {
		deleteDir()
		git url: 'https://github.com/redhat-developer/rsp-server'
	}

	stage('Build') {
		def mvnHome = tool 'maven3-latest'
		env.PATH="${env.PATH}:${mvnHome}/bin"

		sh 'mvn clean install'
		sh 'mvn clean package -f distribution/pom.xml'

		archiveArtifacts artifacts: 'distribution/target/org.jboss.tools.rsp.distribution-*.zip'	
	}

	stage('Coverage Report') {
		sh '''#!/bin/bash
			bash <(curl -s https://codecov.io/bash) -f target/jacoco-report/jacoco.xml || echo "Codecov did not collect coverage reports"
		'''
	}

	stage('Snapshot') {
		def filesToPush = findFiles(glob: '**/*.zip')
		sh "rsync -Pzrlt --rsh=ssh --protocol=28 ${filesToPush[0].path} ${UPLOAD_LOCATION}/snapshots/"
	}
}

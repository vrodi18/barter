#!/usr/bin/env groovy
package com.lib
import groovy.json.JsonSlurper
import hudson.FilePath


def runPipeline() {
  def commonFunctions = new CommonFunction()
  def triggerUser = commonFunctions.getBuildUser()
  def environment = ""
  def gitCommitHash = ""
  def domain_name = ""
  def branch = "${scm.branches[0].name}".replaceAll(/^\*\//, '')
  def tag = "${TAG_NAME}"
  def k8slabel = "jenkins-pipeline-${UUID.randomUUID().toString()}"
  def timeStamp = Calendar.getInstance().getTime().format('ssmmhh-ddMMYYY',TimeZone.getTimeZone('CST'))
  // Generating the repository name 
  def repositoryName = "${JOB_NAME}"
      .split('/')[0]
      .replace('-fuchicorp', '')
      .replace('-build', '')
      .replace('-deploy', '')

  // Generating the deployment name example-deploy 
  def deployJobName = "${JOB_NAME}"
      .split('/')[0]
      .replace('-build', '-deploy')
  
  if (tag =~ '^v[0-9].[0-9]' || tag =~ '^v[0-9][0-9].[0-9]' ) {
        // if Application release or branch starts with v* example v0.1 will be deployed to prod
        environment = 'prod' 
        repositoryName = repositoryName + '-prod'

  } else if (branch.contains('dev-feature')) {
        // if branch name contains dev-feature then the deploy will be deployed to dev environment 
        environment = 'dev' 
        repositoryName = repositoryName + '-dev-feature'

  } else if (branch.contains('qa-feature')) {
        // if branch name contains q-feature then the deploy will be deployed to qa environment
        repositoryName = repositoryName + '-qa-feature'
        environment = 'qa' 

  } else if (branch.contains('PR')) {
        // PR means Pull requests all PR will be deployed to test namespace 
        repositoryName = repositoryName + '-pr-feature'
        environment = 'test' 

  } else if (branch == 'master' || branch == 'main') {
        // If branch is master it will be deployed to stage environment 
        environment = 'stage' 
        repositoryName = repositoryName + '-stage'
  } 

  node('master') {
      // Getting the base domain name from Jenkins master < example: fuchicorp.com >
      domain_name = sh(returnStdout: true, script: 'echo $DOMAIN_NAME').trim()
  }

  try {
    properties([

      //Delete old build jobs
      buildDiscarder(
        logRotator(artifactDaysToKeepStr: '',
        artifactNumToKeepStr: '',
        daysToKeepStr: '',
        numToKeepStr: '4')), [$class: 'RebuildSettings', autoRebuild: false, rebuildDisabled: false],
         
      parameters([
        booleanParam(defaultValue: false, description: 'Click this if you would like to deploy to latest', name: 'PUSH_LATEST'),
        booleanParam(defaultValue: false, description: 'Pleas select this to be able to run the on debug mode', name: 'debugMode')
      ])    
    ])

      if (triggerUser != "AutoTrigger") {
        // If job is not trriggered from github or automatically it will check for validation 
        commonFunctions.validateDeployment(triggerUser, environment)
      } else {
        println("The job is triggereted automatically and skiping the validation !!!")
      }

      def slavePodTemplate = """
      metadata:
        labels:
          k8s-label: ${k8slabel}
        annotations:
          jenkinsjoblabel: ${env.JOB_NAME}-${env.BUILD_NUMBER}
      spec:
        affinity:
          podAntiAffinity:
            requiredDuringSchedulingIgnoredDuringExecution:
            - labelSelector:
                matchExpressions:
                - key: component
                  operator: In
                  values:
                  - jenkins-jenkins-master
              topologyKey: "kubernetes.io/hostname"
        containers:
        - name: sonar-scanner
          image: sonarsource/sonar-scanner-cli
          imagePullPolicy: Always
          command:
          - cat
          tty: true
        - name: docker
          image: docker:latest
          imagePullPolicy: Always
          command:
          - cat
          tty: true
          volumeMounts:
            - mountPath: /var/run/docker.sock
              name: docker-sock
            - mountPath: /etc/secrets/service-account/
              name: google-service-account
        - name: fuchicorptools
          image: fuchicorp/buildtools
          imagePullPolicy: Always
          command:
          - cat
          tty: true
          volumeMounts:
            - mountPath: /var/run/docker.sock
              name: docker-sock
            - mountPath: /etc/secrets/service-account/
              name: google-service-account
        serviceAccountName: common-service-account
        securityContext:
          runAsUser: 0
          fsGroup: 0
        volumes:
          - name: google-service-account
            secret:
              secretName: google-service-account
          - name: docker-sock
            hostPath:
              path: /var/run/docker.sock
    """

  podTemplate(name: k8slabel, label: k8slabel, yaml: slavePodTemplate, showRawYaml: params.debugMode) {
      node(k8slabel) {
        timestamps {
        container('fuchicorptools') {
          stage("Pulling the code") {
            checkout scm
            gitCommitHash = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
          }
          dir("${WORKSPACE}/deployments/docker") {
            stage('SonarQube Scanning') {
              container('sonar-scanner') {
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "sonarqube-admin-access", usernameVariable: 'ADMIN_USER', passwordVariable: 'ADMIN_PASSWORD']]) {
                  try {
                    def sonarQubeUrl = "https://sonarqube.${domain_name}"
                    project = commonFunctions.createProject("${repositoryName}-${gitCommitHash}", "${repositoryName}-${gitCommitHash}", sonarQubeUrl)
                    println("Created SonarQube Project <${repositoryName}-${gitCommitHash}>")
                    
                    token = commonFunctions.genToken("${repositoryName}-${gitCommitHash}-${BUILD_NUMBER}", sonarQubeUrl, "${ADMIN_USER}", "${ADMIN_PASSWORD}")
                    println("Created another token for this bild.")

                    sh """
                    #!/bin/bash
                    sonar-scanner \
                      -Dsonar.projectKey="${repositoryName}-${gitCommitHash}" \
                      -Dsonar.sources=. \
                      -Dsonar.host.url="${sonarQubeUrl}" \
                      -Dsonar.login="${token}"
                    """
                  } catch (e) {
                    println("""SonarQube Scaning was not applied with following error""")
                    println(e.getMessage())
                  }
                  
                }
              }
            }

            stage('Build docker image') {
              // Build the docker image
              dockerImage = docker.build(repositoryName, "--build-arg environment=${environment.toLowerCase()} --build-arg version=${branch.toLowerCase()} .")

            }
          
            stage('Push image') {

              // Keeping trying to login to docker private registry delay is 3 seconds 
              withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "nexus-docker-creds", usernameVariable: 'docker_username', passwordVariable: 'docker_password']]) {
                sh """#!/bin/bash -e
                until docker login --username ${env.docker_username} --password ${env.docker_password} https://docker.${domain_name}
                do
                  echo "Trying to login to docker private system"
                  sleep 3
                done
                """
              }

              // Push image to the Nexus with new release
              docker.withRegistry("https://docker.${domain_name}", 'nexus-docker-creds') {
                  dockerImage.push("${gitCommitHash}") 

                  if (params.PUSH_LATEST) {
                    dockerImage.push("latest")
                }
              }
            }
           }


          stage("Clean up") {

            // Cleaning all docker image to keep the nodes clean 
            sh "docker rmi --no-prune docker.${domain_name}/${repositoryName}:${gitCommitHash}"
            if (params.PUSH_LATEST) {
              sh "docker rmi --no-prune docker.${domain_name}/${repositoryName}:latest"
            }
          }

          stage("Trigger Deploy") {

            // Triggering the example-deploy job with following params 
            // <terraform apply: true, selectedDockerImage: buildedDockerImage, branchName: baseBranch, environment: actualEnvironment>
            build job: "${deployJobName}", 
            parameters: [
                [$class: 'BooleanParameterValue', name: 'terraform_apply',     value: true],
                [$class: 'BooleanParameterValue', name: 'debugMode',     value: params.debugMode],
                [$class: 'StringParameterValue',  name: 'selectedDockerImage', value: "${repositoryName}:${gitCommitHash}"],
                [$class: 'StringParameterValue',  name: 'branchName',          value: branch],
                [$class: 'StringParameterValue',  name: 'environment',         value: "${environment}"]
                ]
          }
        } 
      }
    }
  }
}   catch (e) {
    currentBuild.result = 'FAILURE'
    println("ERROR Detected:")
    println(e.getMessage())
  }
}

return this
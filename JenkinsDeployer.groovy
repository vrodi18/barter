#!/usr/bin/env groovy
package com.lib
import groovy.json.JsonSlurper
import static groovy.json.JsonOutput.*
import hudson.FilePath



def runPipeline() {
  def common_docker          = new JenkinsDeployerPipeline()
  def commonFunctions        = new CommonFunction()
  def triggerUser            = commonFunctions.getBuildUser()
  def branch                 = "${scm.branches[0].name}".replaceAll(/^\*\//, '').replace("/", "-").toLowerCase()
  def gitUrl                 = "${scm.getUserRemoteConfigs()[0].getUrl()}"
  def k8slabel               = "jenkins-pipeline-${UUID.randomUUID().toString()}"
  def allEnvironments        = ['dev', 'qa', 'test', 'stage', 'prod']
  def domain_name            = ""
  def google_bucket_name     = ""
  def google_project_id      = ""
  def debugModeScript = '''
  set -e
  '''

  // Making sure that jenkins is using by default CST time 
  def timeStamp = Calendar.getInstance().getTime().format('ssmmhh-ddMMYYY',TimeZone.getTimeZone('CST'))
  

  node('master') {
      // Getting the base domain name from Jenkins master < example: fuchicorp.com >
      domain_name = sh(returnStdout: true, script: 'echo $DOMAIN_NAME').trim()
      google_bucket_name = sh(returnStdout: true, script: 'echo $GOOGLE_BUCKET_NAME').trim() 
      google_project_id = sh(returnStdout: true, script: 'echo $GOOGLE_PROJECT_ID').trim()
  }

  // Function to get list of docker images from nexus
  def findDockerImageScript = '''
    import groovy.json.JsonSlurper
    def findDockerImages(branchName, domain_name) {
    def versionList = []
    def token       = ""
    def myJsonreader = new JsonSlurper()
    def nexusData = myJsonreader.parse(new URL("https://nexus.${domain_name}/service/rest/v1/components?repository=fuchicorp"))
    nexusData.items.each { if (it.name.contains(branchName)) { versionList.add(it.name + ":" + it.version) } }
    while (true) {
        if (nexusData.continuationToken) {
        token = nexusData.continuationToken
        nexusData = myJsonreader.parse(new URL("https://nexus.${domain_name}/service/rest/v1/components?repository=fuchicorp&continuationToken=${token}"))
        nexusData.items.each { if (it.name.contains(branchName)) { versionList.add(it.name + ":" + it.version) } }
        }
        if (nexusData.continuationToken == null ) { break } }
    if(!versionList) { versionList.add("ImmageNotFound") } 
    return versionList.reverse(true) }
    def domain_name     = "%s"
    def deployment_name = "%s"
    findDockerImages(deployment_name, domain_name)
    '''
  // job name example-fuchicorp-deploy will be < example > 
  def deploymentName = "${JOB_NAME}".split('/')[0].replace('-fuchicorp', '').replace('-build', '').replace('-deploy', '')

  try {    

    properties([

      //Delete old build jobs
      buildDiscarder(
        logRotator(artifactDaysToKeepStr: '',
        artifactNumToKeepStr: '',
        daysToKeepStr: '',
        numToKeepStr: '4')), [$class: 'RebuildSettings', autoRebuild: false, rebuildDisabled: false],
         
         // Trying to build the job
      parameters([

      // Boolean Paramater for terraform apply or not 
      booleanParam(defaultValue: false, 
      description: 'Apply All Changes', 
      name: 'terraform_apply'),

      // Boolean Paramater for terraform destroy 
      booleanParam(defaultValue: false, 
      description: 'Destroy deployment', 
      name: 'terraform_destroy'),

      // ExtendedChoice Script is getting all jobs based on this application
      extendedChoice(bindings: '', description: 'Please select docker image to deploy', 
      descriptionPropertyValue: '', groovyClasspath: '', 
      groovyScript:  String.format(findDockerImageScript, domain_name, deploymentName), multiSelectDelimiter: ',', 
      name: 'selectedDockerImage', quoteValue: false, 
      saveJSONParameterToFile: false, type: 'PT_SINGLE_SELECT', 
      visibleItemCount: 5),
      
      // Branch name to deploy environment 
      gitParameter(branch: '', branchFilter: 'origin/(.*)', defaultValue: 'origin/master', 
      description: 'Please select the branch name to deploy', name: 'branchName', 
      quickFilterEnabled: true, selectedValue: 'NONE', sortMode: 'NONE', tagFilter: '*', type: 'PT_BRANCH_TAG'),
      
      // list of environment getting from <allEnvironments> and defining variable <environment> to deploy 
      choice(name: 'environment', 
      choices: allEnvironments, 
      description: 'Please select the environment to deploy'),

      // Extra configurations to deploy with it 
      text(name: 'deployment_tfvars', 
      defaultValue: 'extra_values = "tools"', 
      description: 'terraform configuration'),

      // Init commands to run before terraform apply 
      text(name: 'init_commands', 
      defaultValue: 'helm ls', 
      description: 'Please add commands you like to run before terraform apply'), 

      // Boolean Paramater for debuging this job 
      booleanParam(defaultValue: false, 
      description: 'If you would like to turn on debuging click this!!', 
      name: 'debugMode')

  

      ]
      )])


      if (triggerUser != "AutoTrigger") {
        commonFunctions.validateDeployment(triggerUser, params.environment)
        
      } else {
        println("The job is triggereted automatically and skiping the validation !!!")
      }

      // Jenkins slave to build this job 
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
          stage("Deployment Info") {

            // Colecting information to show on stage <Deployment Info>
            println(prettyPrint(toJson([
              "Environment" : environment,
              "Deployment" : deploymentName,
              "Builder" : triggerUser,
              "Branch" : branchName,
              "Build": env.BUILD_NUMBER
            ])))
          }
        
          container('fuchicorptools') {

            stage("Polling SCM") {
              checkout([$class: 'GitSCM', 
                        branches: [[name: branchName]], 
                        doGenerateSubmoduleConfigurations: false, 
                        extensions: [], submoduleCfg: [], 
                        userRemoteConfigs: [[credentialsId: 'github-common-access', url: gitUrl]]])
            }

          stage('Generate Configurations') {
            sh """
              mkdir -p ${WORKSPACE}/deployments/terraform/
              cat  /etc/secrets/service-account/credentials.json > ${WORKSPACE}/deployments/terraform/fuchicorp-service-account.json
              ls ${WORKSPACE}/deployments/terraform/
              ## This script should move to docker container to set up ~/.kube/config
              sh /scripts/Dockerfile/set-config.sh
            """
            // Generating all tfvars for the application
            deployment_tfvars += """
              deployment_name        = \"${deploymentName}\"
              deployment_environment = \"${environment}\"
              deployment_image       = \"docker.${domain_name}/${selectedDockerImage}\"
              credentials            = \"./fuchicorp-service-account.json\"
              google_domain_name     = \"${domain_name}\"
              google_bucket_name     = \"${google_bucket_name}\"
              google_project_id      = \"${google_project_id}\"
            """.stripIndent()

            writeFile(
              [file: "${WORKSPACE}/deployments/terraform/deployment_configuration.tfvars", text: "${deployment_tfvars}"]
              )

            if (params.debugMode) {
              sh """
                echo #############################################################
                cat ${WORKSPACE}/deployments/terraform/deployment_configuration.tfvars
                echo #############################################################
              """
              debugModeScript += '''
              set -ex
              export TF_LOG=DEBUG
              echo "Running the scripts on Debug mode!!!"
              '''
            }
            
            try {
                withCredentials([
                    file(credentialsId: "${deploymentName}-config", variable: 'default_config')
                ]) {
                    sh """
                      #!/bin/bash
                      cat \$default_config >> ${WORKSPACE}/deployments/terraform/deployment_configuration.tfvars
                      
                    """
                    if (params.debugMode) {
                      sh """
                        echo #############################################################
                        cat ${WORKSPACE}/deployments/terraform/deployment_configuration.tfvars
                        echo #############################################################
                      """
                    }
                }
            
                println("Found default configurations appanded to main configuration")
            } catch (e) {
                println("Default configurations not found. Skiping!!")
            }
              
          }

          withCredentials([usernamePassword(credentialsId: 'github-common-access', passwordVariable: 'GIT_TOKEN', usernameVariable: 'GIT_USERNAME')]) {
            stage('Terraform Apply/Plan') {
              if (!params.terraform_destroy) {
                if (params.terraform_apply) {
                  dir("${WORKSPACE}/deployments/terraform/") {
                    echo "##### Helm Upgrade ####"
                    sh """#!/bin/bash
                        ${params.init_commands}
                        set -o xtrace 
                        export chart_version=\${environment}-\$(git describe --long --tag --always)
                        export app_version=\$(git describe --tag --always)
                        export chart_path=\$(find . -iname 'Chart.yaml')
                        sed "/^version/s/.*\$/version: \$chart_version/" -i \$chart_path
                        sed "/^appVersion/s/.*\$/appVersion: \$app_version/" -i \$chart_path
                    """.stripIndent()
                    echo "##### Terraform Applying the Changes ####"
                    sh """#!/bin/bash
                        ${debugModeScript}
                        echo "Running set environment script!!"
                        source "./set-env.sh" "deployment_configuration.tfvars"
                        echo "Running terraform apply"
                        echo | terraform apply --auto-approve --var-file="\$DATAFILE"
                    """
                    
                  }

                } else {

                  dir("${WORKSPACE}/deployments/terraform/") {
                    echo "##### Terraform Plan (Check) the Changes #### "
                    sh """#!/bin/bash
                        ${debugModeScript}
                        echo "Running set environment script!!"
                        source "./set-env.sh" "deployment_configuration.tfvars"
                        echo "Running terraform plan"
                        echo | terraform plan --var-file="\$DATAFILE" 
                    """
                  }
                }
              }
            }

            stage('Terraform Destroy') {
              if (!params.terraform_apply) {
                if (params.terraform_destroy) {
                  if ( environment != 'tools' ) {
                    dir("${WORKSPACE}/deployments/terraform/") {
                      echo "##### Terraform Destroing ####"
                      sh """#!/bin/bash
                        ${debugModeScript}
                        echo "Running set environment script!!"
                        source "./set-env.sh" "deployment_configuration.tfvars"
                        echo "Running terraform destroy"
                        echo | terraform destroy --auto-approve -var-file="\$DATAFILE"
                      """
                    }
                  } else {
                    println("""
                      Sorry I can not destroy Tools!!!
                      I can Destroy only following environments dev, qa, test, stage
                    """)
                  }
                }
            }

            if (params.terraform_destroy) {
              if (params.terraform_apply) {
                println("""
                  Sorry you can not destroy and apply at the same time
                """)
                currentBuild.result = 'FAILURE'
              }
            }
          }
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
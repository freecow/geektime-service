#!groovy
pipeline{
    //全局必须带有agent,表明此pipeline执行节点
    agent any
    options {
        //保留最近5个构建历史
        buildDiscarder(logRotator(numToKeepStr: '5'))
        //禁用并发构建
        disableConcurrentBuilds()
        }
    //声明环境变量
    environment {
        //定义镜像仓库地址
        def GIT_URL = 'git@172.16.17.5:develop/app1.git'
        //镜像仓库变量
        def HARBOR_URL = 'harbor.aimanet.cn'
        //镜像项目变量
        def IMAGE_PROJECT = 'myserver'
        //镜像名称变量
        IMAGE_NAME = 'nginx'
        //基于shell命令获取当前时间
        def DATE = sh(script:"date +%F_%H-%M-%S", returnStdout: true).trim()
    }
    
    //参数定义
    parameters {
        //字符串参数，会配置在jenkins的参数化构建过程中
        string(name: 'BRANCH', defaultValue:  'main', description: 'branch select')
        //选项参数，会配置在jenkins的参数化构建过程中
        choice(name: 'DEPLOY_ENV', choices: ['develop', 'production'], description: 'deploy env')
        }

    stages{
        stage("code clone"){
            //#agent { label 'master' }
			steps {
                //删除workDir当前目录
                deleteDir()
                script {
                    if ( env.BRANCH == 'main' ) {
                        git branch: 'main', credentialsId: '065e7efb-934c-4b05-bdcd-51fecb05b9bb', url: 'http://172.16.17.5/develop/app1.git'
                    } else if ( env.BRANCH == 'develop' ) {
                        git branch: 'develop', credentialsId: '065e7efb-934c-4b05-bdcd-51fecb05b9bb', url: 'http://172.16.17.5/develop/app1.git'
                    } else {
                        echo '您传递的分支参数BRANCH ERROR，请检查分支参数是否正确'
                    }
                    //获取clone完成的分支tagId,用于做镜像做tag
                    GIT_COMMIT_TAG = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
		            }
			    }
		    }
	
	    stage("sonarqube-scanner"){
            //#agent { label 'master' }
			steps{
				dir('/var/lib/jenkins/workspace/develop-app1_deploy-20221122') {
                    // some block
                    sh '/apps/sonar-scanner/bin/sonar-scanner -Dsonar.projectKey=develop -Dsonar.projectName=develop-app1 -Dsonar.projectVersion=1.0  -Dsonar.sources=./ -Dsonar.language=py -Dsonar.sourceEncoding=UTF-8'
                    }
			    }
		    } 
		    
	   stage("code build"){
            //#agent { label 'master' } 
			steps{
                dir('/var/lib/jenkins/workspace/develop-app1_deploy-20221122') {
                    // some block
                    sh 'tar czvf frontend.tar.gz ./index.html'
                    }
			    }
		   }
        
		stage("file sync"){
            //#agent { label 'master' }
            steps{
                dir('/var/lib/jenkins/workspace/develop-app1_deploy-20221122') {
                script {
                    stage('file copy') {
                        def remote = [:]
                        remote.name = 'test'
                        remote.host = '172.16.17.2'
                        remote.user = 'root'
                        remote.password = '123456'
                        remote.allowAnyHosts = true
                            //将本地文件put到远端主机
                            sshPut remote: remote, from: 'frontend.tar.gz', into: '/opt/ubuntu-dockerfile'
                        }
                    }
                }
                }
		   }

	    stage("image build"){
            //#agent { label 'master' }
            steps{
                dir('/var/lib/jenkins/workspace/develop-app1_deploy-20221122') {
                script {
                    stage('image put') {
                        def remote = [:]
                        remote.name = 'test'
                        remote.host = '172.16.17.2'
                        remote.user = 'root'
                        remote.password = '123456'
                        remote.allowAnyHosts = true
                            sshCommand remote: remote, command: "cd /opt/ubuntu-dockerfile/ && bash  build-command.sh ${GIT_COMMIT_TAG}-${DATE}"
                        }
                    }
                }
		        }
            }

        stage('docker-compose image update') {
            steps {
                sh """
                    ssh root@172.16.17.2 "echo ${DATE} && cd /data/develop-app1 && sed -i  's#image: harbor.aimanet.cn/myserver/nginx:.*#image: harbor.aimanet.cn/myserver/nginx:${GIT_COMMIT_TAG}-${DATE}#' docker-compose.yml"
                """
                }
            }
        
        stage('docker-compose app update') {
            steps {
                script {
                    stage('image update') {
                        def remote = [:]
                        remote.name = 'docker-server'
                        remote.host = '172.16.17.2'
                        remote.user = 'root'
                        remote.password = '123456'
                        remote.allowAnyHosts = true
                            sshCommand remote: remote, command: "cd /data/develop-app1 && docker-compose pull && docker-compose up -d"
                        }
                    }
                }

            }

        stage('send email') {
            steps {
              sh 'echo send email'
      	    }
            post {
	        always {
	      	  script {
	            mail to: '15392532@qq.com',
                    subject: "Pipeline Name: ${currentBuild.fullDisplayName}",
                    body: " ${env.JOB_NAME} -Build Number-${env.BUILD_NUMBER} \n Build URL-'${env.BUILD_URL}' "
	                 }
                   }

                }
            }
		    
    }
}

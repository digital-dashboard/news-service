pipeline {
    agent { label 'docker-builder' }

    tools {
        maven 'maven'
    }

    stages {
        stage('Checkout') {
            steps {
                // Checkout code from version control
                checkout scm
            }
        }
        stage('Build Details') {
            steps {
                script {
                    def pom = readMavenPom file: 'pom.xml'
                    def appName = pom.artifactId
                    env.APP_VERSION = pom.version
                    echo "Building ${appName} version ${env.APP_VERSION}"
                    currentBuild.displayName = "${appName}-${env.APP_VERSION}-#${env.BUILD_NUMBER}"
                }
            }
        }

        stage('Build & Test') {
            steps {
                sh 'mvn -B clean verify'
            }
        }

        stage('SonarQube Analysis') {
            steps {
                withSonarQubeEnv('SonarQubeServer') {
                    sh "mvn sonar:sonar -Dsonar.projectVersion=${env.APP_VERSION}"
                }
            }
        }

        stage('Quality Gate') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    script {
                        def qg = waitForQualityGate()
                        if (qg.status != 'OK') {
                            error "Pipeline aborted due to quality gate failure: ${qg.status}"
                        }
                    }
                }
            }
        }

        stage('Dependency Check') {
            steps {
                withCredentials([
                    string(credentialsId: 'nvd-api-key', variable: 'NVD_API_KEY'),
                    usernamePassword(credentialsId: 'ossindex-creds', usernameVariable: 'OSS_USER', passwordVariable: 'OSS_PASS')
                ]) {
                    sh '''
                        mvn org.owasp:dependency-check-maven:aggregate \
                            -DnvdApiKey=$NVD_API_KEY \
                            -DossIndexUsername=$OSS_USER \
                            -DossIndexPassword=$OSS_PASS \
                            -DfailBuildOnCVSS=4 \
                            -Dformat=HTML \
                    '''
                }
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'target/dependency-check-report.html', fingerprint: true
            publishHTML(target: [
                reportName : 'Dependency Check',
                reportDir  : 'target',
                reportFiles: 'dependency-check-report.html',
                keepAll    : true
            ])
        }
    }
}
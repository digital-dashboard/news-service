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
                    env.APP_NAME = pom.artifactId
                    env.APP_VERSION = pom.version
                    env.IMAGE_NAME = "${env.APP_NAME}:${env.APP_VERSION}"
                    echo "Building ${env.APP_NAME} version ${env.APP_VERSION}"
                    currentBuild.displayName = "${env.APP_NAME}-${env.APP_VERSION}-#${env.BUILD_NUMBER}"
                }
            }
        }

        stage('Build & Test') {
            steps {
                configFileProvider(
                    [configFile(fileId: env.MAVEN_CONFIG_FILE_ID, variable: 'MAVEN_SETTINGS')]) {
                    sh 'mvn -s $MAVEN_SETTINGS clean package'
                }
            }
        }

        stage('SonarQube Analysis') {
            steps {
                withSonarQubeEnv('SonarQubeServer') {
                    configFileProvider(
                        [configFile(fileId: env.MAVEN_CONFIG_FILE_ID, variable: 'MAVEN_SETTINGS')]) {
                        sh 'mvn -s $MAVEN_SETTINGS sonar:sonar -Dsonar.projectVersion=$env.APP_VERSION'
                    }
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
                    configFileProvider(
                        [configFile(fileId: env.MAVEN_CONFIG_FILE_ID, variable: 'MAVEN_SETTINGS')]) {
                        sh '''
                            mvn -s $MAVEN_SETTINGS org.owasp:dependency-check-maven:aggregate \
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

        stage('Build Docker Image') {
            steps {
                script {
                    docker.build("${env.IMAGE_NAME}")
                }
            }
        }

        stage('Trivy Scan') {
            steps {
                script {
                    def reportFile = "${env.APP_NAME}-${env.APP_VERSION}.json"
                    def vulnerabilityFound = false

                    try {
                        sh "trivy image --exit-code 1 --format json --output ~/trivy-reports/${reportFile} ${env.IMAGE_NAME}"
                    } catch (Exception e) {
                        vulnerabilityFound = true
                        echo "Trivy found vulnerabilities in ${env.IMAGE_NAME}."
                    }

                    if (vulnerabilityFound) {
                        echo "Deleting vulnerable image ${env.IMAGE_NAME} from local machine..."
                        // -f (force) is often necessary if the image is currently in use or referenced.
                        sh "docker rmi -f ${env.IMAGE_NAME}"

                        // Optional: Fail the build to indicate a security gate failure
                        error "Security scan failed: Vulnerabilities found in ${env.IMAGE_NAME}. Image deleted."
                    } else {
                        echo "Trivy scan completed successfully. No critical vulnerabilities found in ${env.IMAGE_NAME}."
                    }
                }
            }
        }

        stage('Push Docker Image') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'docker-registry-creds', usernameVariable: 'DOCKERHUB_USER', passwordVariable: 'DOCKERHUB_PASS')]) {
                    script {
                        docker.withRegistry(env.NEXUS_SNAPSHOT_REGISTRY_URL, 'docker-registry-creds') {
                            docker.image("${env.IMAGE_NAME}").push()
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'target/dependency-check-report.html', fingerprint: true, onlyIfSuccessful: false
            publishHTML(target: [
                reportName : 'Dependency Check',
                reportDir  : 'target',
                reportFiles: 'dependency-check-report.html',
                keepAll    : true
            ])

            script {
                def reportPath = "~/trivy-reports/${env.APP_NAME}-${env.APP_VERSION}.json"
                if (fileExists(reportPath)) {
                    archiveArtifacts artifacts: reportPath, fingerprint: true, onlyIfSuccessful: false
                    publishHTML(target: [
                        reportName : 'Trivy Vulnerability Report',
                        reportDir  : '~/trivy-reports',
                        reportFiles: "${env.APP_NAME}-${env.APP_VERSION}.json",
                        keepAll    : true
                    ])
                } else {
                    echo "No Trivy report found for archiving."
                }
            }
        }
    }
}
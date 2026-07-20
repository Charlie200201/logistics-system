pipeline {
    agent any

    environment {
        DOCKER_REGISTRY = 'localhost:5000'
        MAVEN_HOME = tool 'maven-3.8'
    }

    stages {
        stage('Checkout') {
            steps {
                echo '=== 从 Git 仓库拉取代码 ==='
                git url: 'http://localhost:3000/test/logistics-system.git',
                    branch: 'main',
                    credentialsId: 'gogs-credentials'
            }
        }

        stage('Build') {
            steps {
                echo '=== Maven 编译打包 ==='
                dir('logistics-system-parent') {
                    sh 'mvn clean package -DskipTests'
                }
            }
        }

        stage('Test') {
            steps {
                echo '=== 运行单元测试 ==='
                dir('logistics-system-parent') {
                    sh 'mvn test'
                }
            }
        }

        stage('Docker Build') {
            parallel {
                stage('user-service image') {
                    steps {
                        dir('logistics-system-parent/user-service') {
                            sh 'docker build -t logistics-user-service:latest .'
                        }
                    }
                }
                stage('product-service image') {
                    steps {
                        dir('logistics-system-parent/product-service') {
                            sh 'docker build -t logistics-product-service:latest .'
                        }
                    }
                }
                stage('order-service image') {
                    steps {
                        dir('logistics-system-parent/order-service') {
                            sh 'docker build -t logistics-order-service:latest .'
                        }
                    }
                }
                stage('logistics-service image') {
                    steps {
                        dir('logistics-system-parent/logistics-service') {
                            sh 'docker build -t logistics-logistics-service:latest .'
                        }
                    }
                }
                stage('gateway-service image') {
                    steps {
                        dir('logistics-system-parent/gateway-service') {
                            sh 'docker build -t logistics-gateway-service:latest .'
                        }
                    }
                }
            }
        }

        stage('Deploy') {
            steps {
                echo '=== 部署服务到 Docker 容器 ==='
                script {
                    def services = [
                        [name: 'user-service', port: 8081],
                        [name: 'product-service', port: 8082],
                        [name: 'order-service', port: 8083],
                        [name: 'logistics-service', port: 8084],
                        [name: 'gateway-service', port: 8085]
                    ]
                    services.each { svc ->
                        sh """
                            docker stop logistics-${svc.name} 2>/dev/null || true
                            docker rm logistics-${svc.name} 2>/dev/null || true
                            docker run -d \
                                --name logistics-${svc.name} \
                                --network host \
                                -p ${svc.port}:${svc.port} \
                                logistics-${svc.name}:latest
                        """
                    }
                }
                echo '=== 部署完成 ==='
            }
        }
    }

    post {
        success {
            echo '流水线执行成功!'
        }
        failure {
            echo '流水线执行失败，请检查日志。'
        }
    }
}

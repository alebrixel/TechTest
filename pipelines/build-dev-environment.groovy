pipeline {
    agent {
        label 'docker-host'
    }
    options {
        disableConcurrentBuilds()
        disableResume()
    }

    parameters {
        string(name: 'ENVIRONMENT_NAME', defaultValue: 'development')
        string(name: 'MYSQL_PASSWORD', defaultValue: 'password')
        string(name: 'MYSQL_PORT', defaultValue: '3306')

        booleanParam(name: 'SKIP_STEP_1', defaultValue: false, description: 'STEP 1 - RE-CREATE DOCKER IMAGE')
    }
  
    stages {
        stage('Create MySQL Database') {
            steps {
                script {
                    def mysqlContainer = docker.build("mysql:latest", "--build-arg MYSQL_ROOT_PASSWORD=${params.MYSQL_PASSWORD}")
                    mysqlContainer.run("-d -p ${params.MYSQL_PORT}:3306 --name=mydb_${params.ENVIRONMENT_NAME}")
                }
            }
        }
    }
    validate {
        try {
            def port = Integer.parseInt(params.MYSQL_PORT)
            if (port < 1 || port > 65535) {
                error("Invalid MySQL port value. Please enter a valid port number between 1 and 65535.")
            }
        } catch (NumberFormatException e) {
            error("Invalid MySQL port value. Please enter a valid number.")
        }
    }
    stage('Create Departments Table') {
            steps {
                sh '''
                docker exec ${params.ENVIRONMENT_NAME}-mysql mysql -u root -p${params.MYSQL_PASSWORD} -e "CREATE DATABASE DEVAPP;
                USE DEVAPP;
                CREATE TABLE departments (DEPT INT(4), DEPT_NAME VARCHAR(250));
                INSERT INTO departments (DEPT, DEPT_NAME) VALUES (1, 'IT'), (2, 'HR'), (3, 'Finance');"
                '''
            }
        }
        stage('Prepare Environment') {
            steps {
                sh '''
                useradd developer -p $(openssl passwd -1 ${params.DEVELOPER_PASSWORD})
                '''
            }
        }
        stage('Checkout GIT repository') {
            steps {     
              script {
                git branch: 'master',
                credentialsId: '21f01d09-06da9cc35103',
                url: 'git@github.com:alebrixel/TechTest.git'
              }
            }
        }
        stage('Create latest Docker image') {
            steps {     
              script {
                if (!params.SKIP_STEP_1){    
                    echo "Creating docker image with name $params.ENVIRONMENT_NAME using port: $params.MYSQL_PORT"
                    sh """
                    sed 's/<PASSWORD>/$params.MYSQL_PASSWORD/g' pipelines/include/create_developer.template > pipelines/include/create_developer.sql
                    """

                    sh """
                    docker build pipelines/ -t $params.ENVIRONMENT_NAME:latest
                    """

                }else{
                    echo "Skipping STEP1"
                }
              }
            }
        }
        stage('Start new container using latest image and create user') {
            steps {     
              script {
                
                def dateTime = (sh(script: "date +%Y%m%d%H%M%S", returnStdout: true).trim())
                def containerName = "${params.ENVIRONMENT_NAME}_${dateTime}"
                sh """
                docker run -itd --name ${containerName} --rm -e MYSQL_ROOT_PASSWORD=$params.MYSQL_PASSWORD -p $params.MYSQL_PORT:3306 $params.ENVIRONMENT_NAME:latest
                """

                sh """
                docker exec ${containerName} /bin/bash -c 'mysql --user="root" --password="$params.MYSQL_PASSWORD" < /scripts/create_developer.sql'
                """

                echo "Docker container created: $containerName"

              }
            }
        }
    }

}

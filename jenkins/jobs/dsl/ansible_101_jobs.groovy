// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

// Variables

// Jobs
def installAnsible = freeStyleJob(projectFolderName + "/1_Install_Ansible")
def runAdhocCommands = freeStyleJob(projectFolderName + "/2_Run_Example_Adhoc_Commands")
def runYourAdhocCommand = freeStyleJob(projectFolderName + "/3_Run_Your_Adhoc_Command")

// Resuable work

def setupEnv = '''
set -e
set +x 
cat <<EOF




----- Create a test environment of containers pretending to be servers so that we can run ad hoc commands against them.
----- In real life we would never want Ansible configuring running Docker containers because we'd want them to be 
----- immutable and also not have ssh access enabled.

----- First we'll use Docker to create a control node that will have Ansible installed and be capable of talking to our environment (i.e our docker containers pretending servers).

EOF

#Docker compose will use the current directory name as part of the container names
export THIS_DIR=`echo ${JOB_NAME##*/} | tr '[:upper:]' '[:lower:]' | sed 's/[-_]//g'`

# Hosts file for Asible
cat <<EOF > host
[web]
${THIS_DIR}_web-node-1_1 ansible_ssh_user=app-admin ansible_ssh_private_key_file=/tmp/id_rsa_insecure
${THIS_DIR}_web-node-2_1 ansible_ssh_user=app-admin ansible_ssh_private_key_file=/tmp/id_rsa_insecure

[db]
${THIS_DIR}_db-node_1 ansible_ssh_user=app-admin ansible_ssh_private_key_file=/tmp/id_rsa_insecure
EOF

# Machine with Ansible installed and the credentials to access out temporary environment
cat <<EOF > AnsibleControlDockerfile
FROM centos:latest
RUN yum install -y epel-release && yum install -y ansible && yum install -y openssh-clients
RUN curl -LSs https://raw.githubusercontent.com/mitchellh/vagrant/master/keys/vagrant > /tmp/id_rsa_insecure
RUN chmod 600 /tmp/id_rsa_insecure
ADD host /etc/ansible/hosts
RUN echo \"host_key_checking = False\" >>  /etc/ansible/ansible.cfg
ENV ANSIBLE_HOST_KEY_CHECKING False
EOF

docker build -t ansiblectl${BUILD_NUMBER} -f AnsibleControlDockerfile .



# Our environment build of Docker images pretending to be servers
#Set up network
LAB_NET=lab-net${RANDOM}
docker network create $LAB_NET

#Docker compose file for our environment
cat <<EOF > docker-compose.yml
version: '2'
services:
  web-node-1:
    image: jdeathe/centos-ssh
    expose:
     - \"22\"
    volumes:
     - .:/workspace
     - /var/run/docker.sock:/var/run/docker.sock
    environment:
     - \"SSH_SUDO=ALL=(ALL) NOPASSWD:ALL\"
  web-node-2:
    image: jdeathe/centos-ssh
    expose:
     - \"22\"
    volumes:
     - .:/workspace
     - /var/run/docker.sock:/var/run/docker.sock
    environment:
     - \"SSH_SUDO=ALL=(ALL) NOPASSWD:ALL\"
     
  db-node:
    image: jdeathe/centos-ssh
    expose:
     - \"22\"
    volumes:
     - .:/workspace
     - /var/run/docker.sock:/var/run/docker.sock
    environment:
     - \"SSH_SUDO=ALL=(ALL) NOPASSWD:ALL\"
networks:
  default:
    external:
      name: ${LAB_NET}
EOF

#Bring it up and wait for ssh to start
docker-compose up -d
sleep 5

printf \"\n\n-------- Great we have some containers running let's just prove that.\n\"
set -x
docker-compose ps
docker exec -t ${THIS_DIR}_web-node-1_1 uname -a
docker exec -t ${THIS_DIR}_web-node-2_1 uname -a
docker exec -t ${THIS_DIR}_db-node_1 uname -a
set +x

cat <<EOF


---------------------------------------------------------------------------------------------------------

----- Now we're finally ready to use our control node to run some interesting adhoc Ansible commands.
'''



def cleanUp = '''
---------------------------------------------------------------------------------------------------------

----- Now let's clean up (sort of we�ll leave the images)


EOF

docker-compose kill
docker-compose rm -f
docker rmi -f ansiblectl${BUILD_NUMBER}
docker network rm $LAB_NET
'''



installAnsible.with{
  def desc = "\\n\\n-------- This job installs Ansible \\(in a Docker container for the sake of doing it somewhere clean\\) using Pip.  For more information see: http://docs.ansible.com/ansible/intro_installation.html\\n\\n"
  description(desc)
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    shell('''set -e
            |set +x 
	    |printf  '''.stripMargin() + desc + '''
            |
            |cat <<EOF > Dockerfile
            |FROM centos:latest
            |RUN yum install -y epel-release && yum install -y ansible
            |EOF
            |
            |docker build -t ansible${BUILD_NUMBER} .
            |printf "\\n\\n-------- Let's now test the Ansible install\\n\\n"
            |docker run --rm -t ansible${BUILD_NUMBER} ansible --version
            |printf "\\n\\n-------- That looked good!\\n\\n-------- Let's now just remove our temporary image\\n\\n
            |docker rmi -f ansible${BUILD_NUMBER}
            |'''.stripMargin())
  }
}

runAdhocCommands.with{
  description("This job created a test environment and runs ad hoc Ansible commands against it http://docs.ansible.com/ansible/intro_adhoc.html")
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  label("docker")
  steps {
    shell(setupEnv + '''set -x
            |

----- Let's get Ansible to check the date on all servers: ansible all -a date


EOF
docker run --rm -t --net=${LAB_NET} \
-v /var/run/docker.sock:/var/run/docker.sock \
ansiblectl${BUILD_NUMBER} \
ansible all -a date


cat <<EOF



---------------------------------------------------------------------------------------------------------

----- Let's do the same, but as root: ansible all -b -a date


EOF
docker run --rm -t --net=${LAB_NET} \
-v /var/run/docker.sock:/var/run/docker.sock \
ansiblectl${BUILD_NUMBER} \
ansible all -a date


cat <<EOF





----- Let's get Ansible to ping all web nodes using the Ansible ping module: ansible web -m ping


EOF

docker run --rm -t --net=${LAB_NET} \
-v /var/run/docker.sock:/var/run/docker.sock \
ansiblectl${BUILD_NUMBER} \
ansible web -m ping

cat <<EOF



---------------------------------------------------------------------------------------------------------

----- Now let's use an Ansible module
----- http://docs.ansible.com/ansible/modules_by_category.html
----- http://docs.ansible.com/ansible/list_of_all_modules.html

----- Let's get Ansible to ping the db node using the Ansible ping module: ansible db -m ping
----- http://docs.ansible.com/ansible/ping_module.html

EOF


docker run --rm -t --net=${LAB_NET} \
-v /var/run/docker.sock:/var/run/docker.sock \
ansiblectl${BUILD_NUMBER} \
ansible db -m ping

cat <<EOF


ansible all -m setup


---------------------------------------------------------------------------------------------------------

----- Now let's get Ansible to gather facts about the db node: ansible db -m setup
----- http://docs.ansible.com/ansible/setup_module.html


EOF


docker run --rm -t --net=${LAB_NET} \
-v /var/run/docker.sock:/var/run/docker.sock \
ansiblectl${BUILD_NUMBER} \
ansible db -m setup

cat <<EOF


---------------------------------------------------------------------------------------------------------

----- Now let's get Ansible to add a user to all web servers (need to be root): ansible web -b -m user -a "name=johnd comment=\"John Doe\" uid=1040"
----- http://docs.ansible.com/ansible/git_module.html

EOF


docker run --rm -t --net=${LAB_NET} \
-v /var/run/docker.sock:/var/run/docker.sock \
ansiblectl${BUILD_NUMBER} \
ansible web -b -m user -a "name=johnd comment=\"John Doe\" uid=1040"

cat <<EOF
            |
            |'''.stripMargin() + cleanUp)
  }
}


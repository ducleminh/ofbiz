############################################################
# Dockerfile to build MongoDB container images
# Based on Ubuntu
############################################################

# Set the base image to Ubuntu
FROM debian:latest

# File Author / Maintainer
ADD ofbiz ./usr/ofbiz

run apt-get clean
run rm -vrf /var/lib/apt/lists/*
run apt-get clean
run apt-get update
run apt-get -y upgrade
run apt-get install -y software-properties-common
run apt-get install -y python-software-properties
run echo "deb http://ppa.launchpad.net/webupd8team/java/ubuntu trusty main" > /etc/apt/sources.list.d/webupd8team-java.list
run echo "deb-src http://ppa.launchpad.net/webupd8team/java/ubuntu trusty main" >> /etc/apt/sources.list.d/webupd8team-java.list
run apt-key adv --keyserver keyserver.ubuntu.com --recv-keys EEA14886
#run add-apt-repository -y ppa:webupd8team/java
run apt-get update
run echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | /usr/bin/debconf-set-selections
run apt-get install -y --force-yes oracle-java8-installer

run echo "deb http://apt.postgresql.org/pub/repos/apt/ precise-pgdg main"> /etc/apt/sources.list.d/pgdg.list
run apt-get update
#run apt-get install libc6=2.21-6
#run apt-get install libpq5 
run apt-get install -y --force-yes postgresql-9.4 postgresql-client-9.4 postgresql-contrib-9.4 libpq5
user root
#run ln ­s /srv/ofbiz/etc/nginx.conf /etc/nginx/sites­enabled/ofbiz.conf
#user postgresql
#run createdb ofbiz; create user ofbiz

RUN mkdir $HOME/opt/
RUN wget http://www.eu.apache.org/dist//ant/binaries/apache-ant-1.9.6-bin.tar.gz
RUN tar -xvzf apache-ant-1.9.6-bin.tar.gz && \
  mv apache-ant-1.9.6 /root/opt/apache-ant
ENV ANT_HOME /root/opt/apache-ant 
ENV PATH $PATH:$ANT_HOME/bin

#run apt-get update
#run apt-get install -y git
EXPOSE 8080
EXPOSE 8443

#run mkdir /usr/ofbiz

#this is for setting up postgres
run mkdir /usr/local/postgres
run mkdir /usr/local/postgres/data
run cd /usr/local/postgres
run chown postgres: /usr/local/postgres/data
#run chmod 600 /usr/local/postgres/data

user postgres
RUN /etc/init.d/postgresql start &&\
    createuser -S -D -R ofbiz &&\
	createdb -O ofbiz ofbiz 

user root


RUN echo "listen_addresses='*'" >> /etc/postgresql/9.4/main/postgresql.conf
VOLUME  ["/etc/postgresql", "/var/log/postgresql", "/var/lib/postgresql"]

user root
run apt-get -y --force-yes install sudo

#RUN apt-get install -y supervisor
#ADD supervisord.conf /etc/supervisor/conf.d/supervisord.conf

#ENTRYPOINT ["/usr/bin/supervisord"]


#EXPOSE 5432

#run touch ~/.pgpass
#run chmod 0600 ~/.pgpass

#run echo *:*:*:ofbiz:ofbiz > ~/.pgpass



#user postgres

#run export command=“alter role ofbiz password ofbiz;”
#RUN /etc/init.d/postgresql start &&\
#	psql -d ofbiz &&\
#	alter role ofbiz password ‘ofbiz’;


user root

#local   all             postgres                                peer
#run sed -i ’23s/peer/trust/g’ /etc/postgresql/9.4/main/pg_hba.conf
#run awk ‘NR==1,/peer/{sub(/peer/,trust)}’ /etc/postgresql/9.4/main/pg_hba.conf
#run apt-get install -y vim
#run rm -vrf /etc/postgresql/9.4/main/pg_hba.conf
#run echo “local   all             postgres                                trust” > /etc/postgresql/9.4/main/pg_hba.conf


workdir /usr/ofbiz
cmd sed 's/peer/md5/g' /etc/postgresql/9.4/main/pg_hba.conf &&\
 /etc/init.d/postgresql start &&\
 sudo -u postgres psql -c "alter role ofbiz password 'ofbiz';" &&\
 #ant load-demo &&\
 ant build &&\
 ant start
#CMD [‘/usr/lib/postgresql/9.4/bin/postgres’, ‘-D’, ‘/var/lib/postgresql/9.4/main’, ‘-c’, ‘config_file=/etc/postgresql/9.4/main/postgresql.conf’, ‘ant start’]


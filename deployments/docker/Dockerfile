
FROM python

#COPY ./kubeconfig /root/.kube/config

## Copying all django app to docker container 
COPY ./barter /website

## Changing the workspace
WORKDIR /website

## Putting inside Docker file which environment is 
ARG environment
ARG version
ENV ENVIRONMENT=$environment
ENV RELEASE=$version


## Installing all libraries 
RUN pip install -r requirements.txt

## Expose the port for the webapp
EXPOSE 5000

## Install kubectl and make sure excecutable
RUN curl -LO https://storage.googleapis.com/kubernetes-release/release/v1.13.0/bin/linux/amd64/kubectl
RUN chmod +x ./kubectl
RUN mv ./kubectl /usr/local/bin/kubectl

## Making sure that application will run on first start 
CMD [ "/bin/sh", "-c", "sleep 10 && python manage.py migrate --run-syncdb && python manage.py runserver 0.0.0.0:5000 --noreload"]
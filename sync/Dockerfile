FROM docker.io/alpine/git:v2.36.3
RUN apk add --no-cache bash
RUN apk add --no-cache curl
ADD sync.sh /usr/local/bin
ADD passwd /etc/passwd
ENTRYPOINT /usr/local/bin/sync.sh

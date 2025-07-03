FROM docker.io/library/debian:bookworm-slim
RUN apt-get update && apt-get install -y git curl bash ca-certificates && apt-get clean
ADD sync.sh /usr/local/bin
ADD passwd /etc/passwd
ENTRYPOINT /usr/local/bin/sync.sh

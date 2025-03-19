docker build -t static-lib -f Dockerfile.native .
docker run --rm static-lib:latest
docker run --rm -ti static-lib:latest
docker run --rm -ti -v /c/prj/testme:/mnt/testme:rw -w /mnt/testme static-lib:latest

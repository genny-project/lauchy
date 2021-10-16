#!/bin/bash
#export DDT_URL=http://internmatch.genny.life:8280
export FORCE_CACHE_USE_API=TRUE
./mvnw clean quarkus:dev -Ddebug=5556


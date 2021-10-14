#!/bin/bash
jps -l | grep validation | cut -d" " -f1 | xargs kill -9


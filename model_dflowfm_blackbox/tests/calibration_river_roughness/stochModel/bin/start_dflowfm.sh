#! /bin/sh
#
echo "script : start_dflowfm.sh"
echo "pwd : $PWD"
echo "args : $1"
#module load dflowfm
echo "run_dflowfm.sh --autostartstop $1"
run_dflowfm.sh --autostartstop $1
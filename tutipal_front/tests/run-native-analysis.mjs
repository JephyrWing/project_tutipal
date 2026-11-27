import {spawn} from 'node:child_process'
import {mkdtemp,rm} from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import electron from 'electron'
const profile=await mkdtemp(path.join(os.tmpdir(),'pt45-selected-'))
const env={...process.env,PT45_CAPTURE_PROFILE:profile};delete env.ELECTRON_RUN_AS_NODE
let code=1
try{
 const child=spawn(electron,['tests/native-analysis-capture.cjs'],{env,stdio:'inherit',windowsHide:true})
 code=await new Promise((resolve,reject)=>{child.on('error',reject);child.on('exit',c=>resolve(c??1))})
}finally{await rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:200})}
process.exitCode=code

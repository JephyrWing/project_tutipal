import {test} from 'node:test'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
const {createAuthBoundary}=createRequire(import.meta.url)('../electron/authBoundary.cjs')
test('logout and cookie changes revoke the current analysis immediately',()=>{
 let stops=0;const auth=createAuthBoundary(()=>stops++)
 const previous=auth.revision();auth.revoke()
 assert.equal(stops,1);assert.notEqual(auth.revision(),previous)
})
test('a late 401 from the old account cannot stop the replacement account',()=>{
 let stops=0;const auth=createAuthBoundary(()=>stops++)
 const old=auth.revision();auth.revoke();auth.unauthorized(old)
 assert.equal(stops,1)
 auth.unauthorized(auth.revision());assert.equal(stops,2)
})

import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readdir, rm } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
import ts from 'typescript';

test('compiled server entry loads using emitted JavaScript files', async () => {
  const root=await mkdtemp(path.resolve('.build-smoke-'));
  try {
    await writeFile(path.join(root,'package.json'),'{"type":"module"}');
    const config=ts.readConfigFile('tsconfig.json',ts.sys.readFile);
    const options=ts.convertCompilerOptionsFromJson(config.config.compilerOptions,'.').options;
    const files=[];
    for(const directory of ['src','api']) for(const name of await readdir(directory)) {
      if(name.endsWith('.ts'))files.push(path.resolve(directory,name));
    }
    ts.createProgram(files,{...options,noEmit:false,rootDir:path.resolve('.'),outDir:root}).emit();
    const url=pathToFileURL(path.join(root,'api/index.js')).href;
    const run=spawnSync(process.execPath,['--input-type=module','-e',`const module=await import(${JSON.stringify(url)}); if(typeof module.default!=='function')process.exit(2);`],{encoding:'utf8'});
    assert.equal(run.status,0,run.stderr);
  } finally {await rm(root,{recursive:true,force:true});}
});

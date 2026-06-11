import { chromium } from 'playwright-core';
const b=await chromium.launch({executablePath:'/usr/bin/chromium',args:['--no-sandbox']});
const p=await b.newPage({viewport:{width:1500,height:850}});
await p.goto('http://localhost:8765/SoCLess.html',{waitUntil:'networkidle'});
await p.waitForSelector('[data-box-inst]');
await p.screenshot({path:'/tmp/socless-view/datapath.png'});
await b.close();

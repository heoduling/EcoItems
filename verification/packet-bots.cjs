// Exact protocol-776, test-only receive counters. Compression must remain disabled.
// They perform real logins and issue real command packets; server-side probe reads inventories/SQL mailboxes.
const net = require('net');
const http = require('http');
const fs = require('fs');
const crypto = require('crypto');
const ids = JSON.parse(fs.readFileSync(__dirname + '/packet-ids.json', 'utf8'));
const bots = new Map();
const inverseIds = Object.fromEntries(Object.entries(ids).filter(([k,v])=>typeof v==='object')
  .map(([map,values])=>[map,Object.fromEntries(Object.entries(values).map(([name,id])=>[id,name.replace('minecraft:','')]))]));
function freshCounts(){return {logicalPackets:0,bundleDelimiters:0,encodedBytes:0,framingBytes:0,byType:{}};}
function vi(n) { const out=[]; do { let b=n&127; n >>>= 7; if(n) b|=128; out.push(b); }while(n); return Buffer.from(out); }
function rv(b, start=0) { let n=0; for(let i=0;i<5;i++){ if(start+i>=b.length) return null; const x=b[start+i]; n|=(x&127)<<(7*i); if(!(x&128)) return [n>>>0,start+i+1]; } throw Error('bad VarInt'); }
function str(s) { const b=Buffer.from(s,'utf8'); return Buffer.concat([vi(b.length),b]); }
function uuid(name) { const b=crypto.createHash('md5').update('OfflinePlayer:'+name).digest(); b[6]=(b[6]&15)|48; b[8]=(b[8]&63)|128; return b; }
function key(map, s) { const n=ids[map]['minecraft:'+s]; if(n===undefined) throw Error('missing packet '+map+'.'+s); return n; }
class Bot {
  constructor(name) {
    this.name=name; this.state='login'; this.buffer=Buffer.alloc(0); this.error=null;
    this.containerId=0; this.containerStateId=0; this.clicksSent=0;
    this.counts=freshCounts();this.sent=freshCounts();this.epoch=Date.now();
    this.socket=net.createConnection({host:'127.0.0.1',port:25694});
    this.socket.setNoDelay(true);
    this.socket.on('connect',()=>{
      const port=Buffer.alloc(2); port.writeUInt16BE(25694);
      this.packet(0,Buffer.concat([vi(ids.protocol),str('127.0.0.1'),port,vi(2)]));
      this.packet(key('loginIn','hello'),Buffer.concat([str(name),uuid(name)]));
    });
    this.socket.on('data',b=>{ try { this.buffer=Buffer.concat([this.buffer,b]); this.parse(); } catch(e){this.error=e.message;this.socket.destroy();} });
    this.socket.on('error',e=>{this.error=e.message;});
    this.socket.on('close',()=>{this.state='closed'; console.log('BOT_CLOSED',name,this.error||'normal');});
  }
  packet(id,payload=Buffer.alloc(0)){
    const data=Buffer.concat([vi(id),payload]);const header=vi(data.length);
    const map=this.state==='login'?'loginIn':this.state==='config'?'configIn':'playIn';
    const type=inverseIds[map]?.[id]||map+':'+id;
    this.count(this.sent,type,data.length,header.length);this.socket.write(Buffer.concat([header,data]));
  }
  count(target,type,bytes,framing){
    if(type==='bundle_delimiter')target.bundleDelimiters++;else target.logicalPackets++;
    target.encodedBytes+=bytes;target.framingBytes+=framing;
    const row=target.byType[type]||(target.byType[type]={count:0,encodedBytes:0,framingBytes:0});
    row.count++;row.encodedBytes+=bytes;row.framingBytes+=framing;
  }
  reset(){this.counts=freshCounts();this.sent=freshCounts();this.epoch=Date.now();}
  parse(){
    while(true){const h=rv(this.buffer); if(!h || this.buffer.length<h[1]+h[0]) return;
      const frame=this.buffer.subarray(h[1],h[1]+h[0]); this.buffer=this.buffer.subarray(h[1]+h[0]);
      const p=rv(frame); const id=p[0],payload=frame.subarray(p[1]);
      const map=this.state==='login'?'loginOut':this.state==='config'?'configOut':'playOut';
      const type=inverseIds[map]?.[id]||map+':'+id;this.count(this.counts,type,frame.length,h[1]);this.handle(id,payload);
    }
  }
  handle(id,p){
    const map=this.state==='login'?'loginOut':this.state==='config'?'configOut':'playOut';
    const type=inverseIds[map]?.[id];
    if(type==='disconnect'){this.error='server disconnect '+p.toString('hex').slice(0,500);this.socket.end();return;}
    if(this.state==='login'){
      if(type==='login_compression') throw Error('compression should be disabled');
      if(type==='login_finished'){
        this.packet(key('loginIn','login_acknowledged')); this.state='config';
      }
    }else if(this.state==='config'){
      if(type==='select_known_packs') this.packet(key('configIn','select_known_packs'),p);
      if(type==='keep_alive') this.packet(key('configIn','keep_alive'),p);
      if(type==='ping') this.packet(key('configIn','pong'),p);
      if(type==='finish_configuration'){
        this.packet(key('configIn','finish_configuration'));this.state='play';console.log('BOT_PLAY',this.name);
      }
    }else if(this.state==='play'){
      if(type==='open_screen') { const header=rv(p); this.containerId=header[0];this.screenType=rv(p,header[1])[0];this.containerStateId=0; }
      if(type==='container_set_content') { const c=rv(p); if(c[0]===this.containerId) this.containerStateId=rv(p,c[1])[0]; }
      if(type==='container_set_slot') { const c=rv(p); if(c[0]===this.containerId) this.containerStateId=rv(p,c[1])[0]; }
      if(type==='container_close') this.containerId=0;
      if(type==='keep_alive') this.packet(key('playIn','keep_alive'),p);
      if(type==='ping') this.packet(key('playIn','pong'),p);
      if(type==='player_position') this.packet(key('playIn','accept_teleportation'),vi(rv(p)[0]));
    }
  }
  send(command){if(this.state!=='play') throw Error(this.name+' not in play: '+this.state);this.packet(key('playIn','chat_command'),str(command));}
  click(slot=-999){
    if(this.state!=='play' || !this.containerId) throw Error('No open container');
    const rawSlot=Buffer.alloc(2); rawSlot.writeInt16BE(slot);
    // Exact core codec: ContainerId, VarInt state, short slot, byte button,
    // VarInt PICKUP, empty changed-slots map, optional EMPTY carried stack.
    this.packet(key('playIn','container_click'),Buffer.concat([
      vi(this.containerId),vi(this.containerStateId),rawSlot,Buffer.from([0]),vi(0),vi(0),Buffer.from([0])
    ])); this.clicksSent++;
  }
  closeWindow(){this.packet(key('playIn','container_close'),vi(this.containerId));this.containerId=0;}
}
if(process.argv.includes('--selftest')){
  const assert=require('assert');const bot=Object.create(Bot.prototype);
  bot.state='play';bot.buffer=Buffer.alloc(0);bot.counts=freshCounts();bot.handle=()=>{};
  const make=(id,payload)=>{const body=Buffer.concat([vi(id),payload]);return Buffer.concat([vi(body.length),body]);};
  const frames=[make(0,Buffer.alloc(0)),make(key('playOut','open_screen'),Buffer.alloc(300)),make(key('playOut','container_set_slot'),Buffer.alloc(6)),make(0,Buffer.alloc(0))];
  const source=Buffer.concat(frames);
  for(let offset=0;offset<source.length;offset+=7){bot.buffer=Buffer.concat([bot.buffer,source.subarray(offset,offset+7)]);bot.parse();}
  assert.equal(bot.counts.logicalPackets,2);assert.equal(bot.counts.bundleDelimiters,2);
  assert.equal(bot.counts.byType.open_screen.count,1);assert.equal(bot.counts.byType.container_set_slot.count,1);
  assert.equal(bot.counts.encodedBytes+bot.counts.framingBytes,source.length);assert.equal(bot.buffer.length,0);
  const stateBot=Object.create(Bot.prototype);stateBot.state='play';stateBot.containerId=3;stateBot.containerStateId=0;
  stateBot.handle(key('playOut','container_set_slot'),Buffer.concat([vi(3),vi(7),Buffer.alloc(3)]));
  assert.equal(stateBot.containerStateId,7,'Large-content slot fallback updates stateId');
  stateBot.handle(key('playOut','container_set_content'),Buffer.concat([vi(3),vi(9),Buffer.alloc(2)]));
  assert.equal(stateBot.containerStateId,9,'Normal content updates stateId');
  let clickPayload;stateBot.packet=(id,payload)=>{assert.equal(id,key('playIn','container_click'));clickPayload=payload;};
  stateBot.click(-999);const cid=rv(clickPayload),sid=rv(clickPayload,cid[1]);
  assert.equal(cid[0],3);assert.equal(sid[0],9,'Click publishes latest remote state');
  const legacyBot=Object.create(Bot.prototype);legacyBot.state='play';legacyBot.containerId=3;legacyBot.containerStateId=0;
  legacyBot.packet=(id,payload)=>clickPayload=payload;legacyBot.click(-999);
  const legacyCid=rv(clickPayload),legacySid=rv(clickPayload,legacyCid[1]);
  assert.equal(legacySid[0],0);assert.notEqual(legacySid[0],9,'Negative control reproduces stale state mismatch');
  console.log('PACKET_COUNTER_SELFTEST_PASS split frames + 2-byte length + delimiter separation + slot/content state IDs + stale/correct click controls');
}else http.createServer(async(req,res)=>{
  try{
    let data='';for await(const b of req)data+=b;
    const input=data?JSON.parse(data):{action:'status'};
    if(input.action==='connect'){
      const existing=bots.get(input.name);if(existing&&existing.state!=='closed')throw Error('already connected');
      bots.set(input.name,new Bot(input.name));
    }
    if(input.action==='send')bots.get(input.name).send(input.command.replace(/^\//,''));
    if(input.action==='commands'){if(!Array.isArray(input.commands)||input.commands.length>10)throw Error('Bounded command batch required');for(const command of input.commands)bots.get(input.name).send(command.replace(/^\//,''));}
    if(input.action==='reset') {const selected=input.name?[bots.get(input.name)]:[...bots.values()];selected.forEach(b=>b.reset());}
    if(input.action==='burst'){
      const count=input.count||1,intervalMs=input.intervalMs??100;
      if(count<1||count>10000||intervalMs<50)throw Error('Bounded burst requires count 1..10000 and interval >=50ms');
      for(let i=0;i<count;i++){bots.get(input.name).send(input.command.replace(/^\//,''));await new Promise(resolve=>setTimeout(resolve,intervalMs));}
    }
    if(input.action==='click'){
      for(let i=0;i<(input.count||1);i++){
        bots.get(input.name).click(input.slot??-999);
        await new Promise(resolve=>setTimeout(resolve,50));
      }
    }
    if(input.action==='close_window')bots.get(input.name).closeWindow();
    if(input.action==='quit')bots.get(input.name).socket.end();
    const result=Array.from(bots.values(),b=>({name:b.name,state:b.state,error:b.error,
      containerId:b.containerId,containerStateId:b.containerStateId,screenType:b.screenType,clicksSent:b.clicksSent,
      sinceEpochMs:Date.now()-b.epoch,received:b.counts,sent:b.sent}));
    res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(result));
  }catch(e){res.writeHead(500);res.end(e.stack);}
}).listen(25695,'127.0.0.1',()=>console.log('BOT_CONTROL_READY 25695 protocol='+ids.protocol+' sourceSha256='+crypto.createHash('sha256').update(fs.readFileSync(__filename)).digest('hex')));



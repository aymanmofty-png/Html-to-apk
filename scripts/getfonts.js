// يجلب الخطوط العربية من حزم @fontsource ويضمّها داخل www/fonts (لا يفشل البناء إن تعذّر بعضها)
const {execSync}=require('child_process'),fs=require('fs'),path=require('path');
const F=[['cairo','Cairo'],['tajawal','Tajawal'],['almarai','Almarai'],['amiri','Amiri'],['reem-kufi','Reem Kufi'],['mada','Mada'],['lateef','Lateef'],['scheherazade-new','Scheherazade New'],['noto-naskh-arabic','Noto Naskh Arabic'],['el-messiri','El Messiri'],['lemonada','Lemonada'],['changa','Changa'],['aref-ruqaa','Aref Ruqaa'],['rakkas','Rakkas'],['marhey','Marhey'],['katibeh','Katibeh'],['noto-kufi-arabic','Noto Kufi Arabic'],['harmattan','Harmattan'],['mirza','Mirza'],['baloo-bhaijaan-2','Baloo Bhaijaan 2']];
const AR='U+0600-06FF,U+200C-200E,U+2010-2011,U+204F,U+2E41,U+FB50-FDFF,U+FE80-FEFC';
const LA='U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+2000-206F,U+2074,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD';
const tmp='/tmp/fsrc';fs.mkdirSync(tmp,{recursive:true});fs.writeFileSync(path.join(tmp,'package.json'),'{"name":"f","version":"1.0.0"}');
const out='www/fonts';fs.mkdirSync(out,{recursive:true});let css='',ok=0;
for(const [pkg,fam] of F){
 try{execSync('npm install --no-save --no-audit --no-fund @fontsource/'+pkg,{cwd:tmp,stdio:'ignore'})}catch(e){console.log('skip',pkg);continue}
 const dir=path.join(tmp,'node_modules/@fontsource',pkg,'files');if(!fs.existsSync(dir)){console.log('nofiles',pkg);continue}
 const fl=fs.readdirSync(dir);let got=0;
 for(const w of [400,700]){for(const [sub,ur] of [['arabic',AR],['latin',LA]]){
  const n=pkg+'-'+sub+'-'+w+'-normal.woff2';if(!fl.includes(n))continue;
  fs.copyFileSync(path.join(dir,n),path.join(out,n));
  css+="@font-face{font-family:'"+fam+"';font-style:normal;font-weight:"+w+";font-display:swap;src:url(fonts/"+n+") format('woff2');unicode-range:"+ur+"}\n";if(sub=='arabic')got=1}}
 if(got)ok++;else console.log('noarabic',pkg);
}
fs.writeFileSync('www/fonts.css',css);console.log('fonts ok',ok,'of',F.length);

/* يحاكي واجهة Capacitor التي تعتمد عليها صفحة التطبيق، فوق جسر أندرويد الأصلي (NativeBridge) */
(function(){
  var NB=window.NativeBridge;if(!NB)return;
  function call(f){
    var a=Array.prototype.slice.call(arguments,1);
    return new Promise(function(ok,no){
      try{var r=JSON.parse(NB[f].apply(NB,a));if(r&&r.error)no(new Error(r.error));else ok(r||{})}
      catch(e){no(e)}
    });
  }
  var Plugins={
    SaveDl:{save:function(o){return call('saveFile',o.name,o.mime||'application/octet-stream',o.data)}},
    Filesystem:{
      mkdir:function(){return Promise.resolve({})},
      writeFile:function(o){return call('writeFile',o.path,o.data,o.directory||'CACHE')}
    },
    Share:{share:function(o){return call('share',o.title||'',o.text||'',(o.files&&o.files[0])||'')}},
    ScreenOrientation:{
      lock:function(o){return call('orient',(o&&o.orientation)||'')},
      unlock:function(){return call('orient','')}
    },
    RoomKeep:{
      start:function(o){return call('keepStart',(o&&o.text)||'')},
      stop:function(){return call('keepStop')},
      battery:function(o){return call('battery',!!(o&&o.ask))},
      notifSettings:function(){return call('notifSettings')}
    },
    StatusBar:{hide:function(){return Promise.resolve()}}
  };
  window.__nb={
    jr:function(f){var a=Array.prototype.slice.call(arguments,1);try{return JSON.parse(NB[f].apply(NB,a))}catch(e){return null}},
    s:function(f){var a=Array.prototype.slice.call(arguments,1);try{return NB[f].apply(NB,a)}catch(e){return null}}
  };
  window.Capacitor={
    isNativePlatform:function(){return true},
    getPlatform:function(){return 'android'},
    registerPlugin:function(n){return Plugins[n]||new Proxy({},{get:function(){return function(){return Promise.reject(new Error('plugin '+n+' missing'))}}})},
    Plugins:Plugins
  };
})();

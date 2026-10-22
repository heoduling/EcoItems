package verification;

import com.willfp.eco.core.display.DisplayModule;
import com.willfp.eco.core.fast.FastItemStack;
import com.willfp.eco.core.registry.Registry;
import com.willfp.ecoitems.items.*;
import com.willfp.ecoitems.display.ItemsDisplay;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.invoke.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.util.*;
import com.sun.management.ThreadMXBean;

/** Isolated exact-JAR oracle, regression and allocation probe. Never shipped in the plugin. */
public final class ItemsProbe extends JavaPlugin {
    MethodHandle oldGet, newGet, oldFast, oldFind;
    DisplayModule oldDisplay;
    final NamespacedKey key = new NamespacedKey("ecoitems", "item");
    final NamespacedKey legacy = new NamespacedKey("ecoweapons", "weapon");
    List<EcoItem> definitions;
    volatile Object sink;
    int checks, comparisons;
    public void onEnable() {
        try {
            ClassLoader parent = ItemsDisplay.class.getClassLoader();
            var loader = new URLClassLoader(new URL[]{new java.io.File(System.getProperty("items.baseline")).toURI().toURL()}, parent) {
                protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
                    if (!(n.equals(ItemUtilsKt.class.getName()) || n.equals(EcoItemFinder.class.getName()) || n.equals(ItemsDisplay.class.getName()))) return super.loadClass(n, resolve);
                    synchronized(getClassLoadingLock(n)) {
                        Class<?> c=findLoadedClass(n);if(c==null)c=findClass(n);if(resolve)resolveClass(c);return c;
                    }
                }
            };
            var lookup=MethodHandles.publicLookup();
            var signature=MethodType.methodType(EcoItem.class,ItemStack.class);
            Class<?> utils=loader.loadClass(ItemUtilsKt.class.getName());
            oldGet=lookup.findStatic(utils,"getEcoItem",signature);
            newGet=lookup.findStatic(ItemUtilsKt.class,"getEcoItem",signature);
            oldFast=lookup.findStatic(utils,"getEcoItem",MethodType.methodType(EcoItem.class,FastItemStack.class));
            Class<?> finder=loader.loadClass(EcoItemFinder.class.getName());
            oldFind=lookup.findVirtual(finder,"find",MethodType.methodType(List.class,ItemStack.class)).bindTo(finder.getField("INSTANCE").get(null));
            oldDisplay=(DisplayModule)loader.loadClass(ItemsDisplay.class.getName()).getField("INSTANCE").get(null);
            // eco/libreforge populate config categories after Bukkit onEnable returns.
            Bukkit.getGlobalRegionScheduler().runDelayed(this,t->{try{
                definitions=EcoItems.INSTANCE.values();check(!definitions.isEmpty(),"configured definitions loaded");
                getLogger().info("[EI] READY definitions="+definitions.size()+" wrapper="+FastItemStack.wrap(definitions.getFirst().getItemStack()).getClass().getName());
            }catch(Throwable e){fail(e);}},80L);
        } catch(Throwable e){fail(e);}
    }
    void fail(Throwable e){getLogger().severe("[EI] FAIL "+e);e.printStackTrace();}
    void check(boolean b,String message){if(!b)throw new AssertionError(message);checks++;}
    public boolean onCommand(CommandSender s,Command c,String label,String[] args){
        if(s instanceof Player p)p.getScheduler().run(this,t->{try{
            check(Bukkit.isOwnedByCurrentRegion(p),"player owner");
            if(args.length>0&&args[0].equals("bench")){
                // Only detached fixtures and immutable definition handles cross this boundary.
                var f=fixtures();new Thread(()->{try{bench(f);}catch(Throwable e){fail(e);}},"items-allocation-platform").start();
            } else behavior(p);
        }catch(Throwable e){fail(e);}},()->fail(new IllegalStateException("retired player")));
        return true;
    }
    ItemStack item(boolean rich,boolean craft){
        var i=new ItemStack(Material.NETHERITE_SWORD);
        if(rich){var m=i.getItemMeta();var lore=new ArrayList<String>();for(int n=0;n<24;n++)lore.add("Unrelated lore line "+n+" with component text");m.setLore(lore);
            for(int n=0;n<16;n++)m.getPersistentDataContainer().set(new NamespacedKey(this,"payload_"+n),PersistentDataType.STRING,"unrelated-data-"+n);
            m.setCustomModelData(124);i.setItemMeta(m);}
        return craft?CraftItemStack.asCraftCopy(i):i;
    }
    <T,Z> void tag(ItemStack i,NamespacedKey k,PersistentDataType<T,Z> type,Z value){var m=i.getItemMeta();m.getPersistentDataContainer().set(k,type,value);i.setItemMeta(m);}
    void remove(ItemStack i,NamespacedKey k){var m=i.getItemMeta();m.getPersistentDataContainer().remove(k);i.setItemMeta(m);}
    EcoItem original(ItemStack i)throws Throwable{return (EcoItem)oldGet.invokeExact(i);}
    interface Checked {Object run()throws Throwable;}
    record Outcome(Object value,String error){}
    Outcome outcome(Checked action){try{return new Outcome(action.run(),null);}catch(Throwable e){return new Outcome(null,e.getClass().getName());}}
    void compare(ItemStack base,Player player,String name)throws Throwable{
        var a=base.clone();var b=base.clone();
        check(outcome(()->original(a)).equals(outcome(()->ItemUtilsKt.getEcoItem(b))),"getter "+name);
        check(a.equals(b),"getter content "+name);
        check(outcome(()->(List<?>)oldFind.invokeExact(a)).equals(outcome(()->EcoItemFinder.INSTANCE.find(b))),"finder "+name);
        check(outcome(()->(EcoItem)oldFast.invokeExact(FastItemStack.wrap(a))).equals(outcome(()->ItemUtilsKt.getEcoItem(FastItemStack.wrap(b)))),"fast getter "+name);
        var da=base.clone();var db=base.clone();
        check(outcome(()->{oldDisplay.display(da,player,new Object[0]);return null;}).equals(outcome(()->{ItemsDisplay.INSTANCE.display(db,player,new Object[0]);return null;})),"display exceptions "+name);
        check(da.equals(db),"display, flags, name and lore "+name);
        for(EcoItem def:definitions)check(outcome(()->def.getCustomItem().matches(base)).equals(outcome(()->Objects.equals(original(base.clone()),def))),"custom predicate "+name+" "+def.getID());
        comparisons++;
    }
    void behavior(Player p)throws Throwable{
        p.setGameMode(GameMode.CREATIVE);p.setInvulnerable(true);p.setGravity(false);p.setHealth(p.getName().equals("Items2")?4:20);
        check(ItemUtilsKt.getEcoItem((ItemStack)null)==null,"null input");
        compare(new ItemStack(Material.AIR),p,"air");
        int n=0;
        for(boolean craft:new boolean[]{false,true})for(boolean rich:new boolean[]{false,true}){
            var i=item(rich,craft);compare(i,p,"plain-"+n++);
            for(int marker=0;marker<6;marker++){
                var x=i.clone();if(marker==0)tag(x,key,PersistentDataType.STRING,"unknown_definition");
                if(marker==1)tag(x,key,PersistentDataType.INTEGER,1);
                if(marker==2)tag(x,legacy,PersistentDataType.INTEGER,1);
                if(marker==3){tag(x,key,PersistentDataType.STRING,"unknown_definition");tag(x,legacy,PersistentDataType.INTEGER,1);}
                if(marker==4)tag(x,key,PersistentDataType.STRING,"");
                if(marker==5){x.setAmount(0);}
                compare(x,p,"marker-"+n++);
            }
            for(var def:definitions){
                var x=i.clone();tag(x,key,PersistentDataType.STRING,def.getID());compare(x,p,"defined-"+n++);compare(x,null,"null-player-"+n++);
                var meta=x.getItemMeta();meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);meta.setDisplayName("Existing custom name");x.setItemMeta(meta);compare(x,p,"changed-meta-"+n++);
                check(ItemUtilsKt.getEcoItem(x)==def,"added visible immediately");remove(x,key);check(ItemUtilsKt.getEcoItem(x)==null,"removed visible immediately");
                ItemUtilsKt.setEcoItem(x,def);check(ItemUtilsKt.getEcoItem(x)==def,"public setter");ItemUtilsKt.setEcoItem(x,null);check(ItemUtilsKt.getEcoItem(x)==null,"public clear");
            }
        }
        migration();
        var conditional=EcoItems.INSTANCE.getByID("probe_condition");check(conditional!=null,"conditional fixture loaded");
        var ci=conditional.getItemStack();compare(ci,p,"player-condition");
        double savedHealth=p.getHealth();
        for(boolean met:new boolean[]{false,true}){
            p.setHealth(met?20:4);
            ci=conditional.getItemStack();compare(ci,p,"live-condition-"+met);
            var hints=new com.willfp.libreforge.ItemProvidedHolder(conditional,ci).getNotMetLines(p);
            ItemsDisplay.INSTANCE.display(ci,p,new Object[0]);
            getLogger().info("[EI] CONDITION state="+met+" blocks="+conditional.getConditions().size()+" hints="+hints+" lore="+ci.getItemMeta().getLore());
            check(!hints.isEmpty()==!met,"condition fixture state");
            check(ci.getItemMeta().getLore().stream().anyMatch(l->ChatColor.stripColor(l).contains("PROBE_HEALTH_REQUIREMENT"))==!met,"condition hint preserved");
        }
        p.setHealth(savedHealth);
        if(p.getName().equals("Items1"))registryBoundary();
        var sword=EcoItems.INSTANCE.getByID("enderite_sword");check(sword!=null,"production sword");
        var target=com.willfp.reforges.reforges.ReforgeTargets.INSTANCE.getByName("enderite_sword");
        check(target!=null&&target.matches(sword.getItemStack()),"Reforges custom target");
        var unmarked=sword.getItemStack();remove(unmarked,key);check(!target.matches(unmarked),"Reforges rejects removed marker");
        p.getInventory().clear();p.getInventory().setItemInMainHand(sword.getItemStack());
        Bukkit.getPluginManager().callEvent(new InventoryCloseEvent(p.getOpenInventory()));
        check(p.getAttribute(Attribute.ATTACK_DAMAGE).getModifiers().stream().anyMatch(m->m.getKey().getNamespace().equals("ecoitems")),"equip adds attributes");
        p.getInventory().setItemInMainHand(new ItemStack(Material.STONE));
        // Exercise the plugin's actual next-tick holder refresh, rather than expect
        // a same-tick inventory mutation to bypass libreforge's existing holder cache.
        ItemAttributeListener.INSTANCE.handleInventoryClick(new InventoryClickEvent(p.getOpenInventory(),InventoryType.SlotType.CONTAINER,0,ClickType.LEFT,InventoryAction.NOTHING));
        p.getScheduler().runDelayed(this,t->{try{
            for(var attr:new Attribute[]{Attribute.ATTACK_DAMAGE,Attribute.ATTACK_SPEED,Attribute.ENTITY_INTERACTION_RANGE})check(p.getAttribute(attr).getModifiers().stream().noneMatch(m->m.getKey().getNamespace().equals("ecoitems")),"unequip restores "+attr);
            getLogger().info("[EI] BEHAVIOR_DONE player="+p.getName()+" checks="+checks+" comparisons="+comparisons+" health="+p.getHealth());
        }catch(Throwable e){fail(e);}},null,3L);
    }
    void migration()throws Throwable{
        var def=definitions.getFirst();
        for(boolean craft:new boolean[]{false,true})for(boolean fast:new boolean[]{false,true})for(int mode=0;mode<4;mode++){
            var i=item(true,craft);String id=mode==2?"unknown_definition":def.getID();tag(i,legacy,PersistentDataType.STRING,id);
            if(mode==1)tag(i,key,PersistentDataType.STRING,"unknown_definition");
            if(mode==3)tag(i,key,PersistentDataType.INTEGER,7);
            var before=i.clone();boolean failed=false;try{original(before);}catch(NullPointerException expected){failed=true;}
            check(failed,"old migration negative control");
            EcoItem value=fast?ItemUtilsKt.getEcoItem(FastItemStack.wrap(i)):ItemUtilsKt.getEcoItem(i);
            check(value==(mode==2?null:def),"legacy result");
            check(!i.getPersistentDataContainer().has(legacy),"legacy removed");
            check(id.equals(i.getPersistentDataContainer().get(key,PersistentDataType.STRING)),"legacy value persisted");
            check(ItemUtilsKt.getEcoItem(i)==value,"migrated second lookup");
            check(i.getItemMeta().getLore().equals(item(true,craft).getItemMeta().getLore()),"migration preserves unrelated lore");
        }
    }
    @SuppressWarnings("unchecked") void registryBoundary()throws Throwable{
        var f=EcoItems.class.getDeclaredField("registry");f.setAccessible(true);var registry=(Registry<EcoItem>)f.get(null);
        var def=definitions.getFirst();var base=def.getItemStack();
        try{
            registry.remove(def);check(ItemUtilsKt.getEcoItem(base)==null&&original(base)==null,"definition deletion visible");
            var replacement=new EcoItem(def.getID(),def.getConfig());registry.register(replacement);
            check(ItemUtilsKt.getEcoItem(base)==replacement&&original(base)==replacement,"same ID reload visible");
        }finally{registry.remove(def.getID());registry.register(def);def.getCustomItem().register();}
        check(ItemUtilsKt.getEcoItem(base)==def,"definition restored");
    }
    record Fixture(String name,ItemStack item){}
    List<Fixture> fixtures(){
        var plain=item(false,true);var rich=item(true,true);var marked=rich.clone();tag(marked,key,PersistentDataType.STRING,definitions.getFirst().getID());
        var markedPlain=plain.clone();tag(markedPlain,key,PersistentDataType.STRING,definitions.getFirst().getID());
        var unknown=rich.clone();tag(unknown,key,PersistentDataType.STRING,"unknown_definition");
        var empty=new ItemStack(Material.AIR);
        var bukkit=item(true,false);var bukkitMarked=bukkit.clone();tag(bukkitMarked,key,PersistentDataType.STRING,definitions.getFirst().getID());
        return List.of(new Fixture("plain-ordinary",plain),new Fixture("rich-ordinary",rich),new Fixture("rich-marked",marked),new Fixture("plain-marked",markedPlain),new Fixture("rich-unknown",unknown),new Fixture("empty",empty),new Fixture("bukkit-rich-ordinary",bukkit),new Fixture("bukkit-rich-marked",bukkitMarked));
    }
    Object operation(String op,boolean old,ItemStack base)throws Throwable{
        return switch(op){
            case "get" -> (EcoItem)(old?oldGet:newGet).invokeExact(base);
            case "find" -> old?(List<?>)oldFind.invokeExact(base):EcoItemFinder.INSTANCE.find(base);
            case "display" -> {var i=base.clone();(old?oldDisplay:ItemsDisplay.INSTANCE).display(i,null,new Object[0]);yield i;}
            default -> throw new AssertionError(op);
        };
    }
    void bench(List<Fixture> fixtures)throws Throwable{
        var bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();check(!Thread.currentThread().isVirtual(),"platform allocation thread");
        bean.setThreadAllocatedMemoryEnabled(true);long id=Thread.currentThread().threadId();check(bean.getThreadAllocatedBytes(id)>=0,"allocation counter supported");
        for(var fixture:fixtures)for(String op:List.of("get","find","display")){
            int count=op.equals("display")?500:4000;
            for(int round=-10;round<16;round++)for(boolean old:round%2==0?new boolean[]{true,false}:new boolean[]{false,true}){
                long bytes=bean.getThreadAllocatedBytes(id),time=System.nanoTime();
                for(int k=0;k<count;k++)sink=operation(op,old,fixture.item());
                time=System.nanoTime()-time;bytes=bean.getThreadAllocatedBytes(id)-bytes;
                if(round>=0)getLogger().info("[EI] METRIC fixture="+fixture.name()+" op="+op+" version="+(old?"old":"new")+" round="+round+" count="+count+" bytes="+bytes+" nanos="+time);
            }
        }
        getLogger().info("[EI] BENCH_DONE checks="+checks);
    }
}

package life.genny.lauchy.live.data;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

@ApplicationScoped
public class InternalProducer {

  @Inject @Channel("blacklists") Emitter<String> blacklists;
  public Emitter<String> getToBlacklists() {
    return blacklists;
  }

  @Inject @Channel("webdata") Emitter<String> webdata;
  public Emitter<String> getToWebData() {
    return webdata;
  }
  
  @Inject @Channel("webcmds") Emitter<String> webcmds;
  public Emitter<String> getToWebCmds() {
    return webcmds;
  }

}


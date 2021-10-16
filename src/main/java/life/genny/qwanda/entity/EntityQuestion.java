package life.genny.qwanda.entity;

import java.lang.invoke.MethodHandles;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;



import life.genny.qwanda.Ask;
import life.genny.qwanda.Link;
import life.genny.qwanda.attribute.Attribute;


public class EntityQuestion implements java.io.Serializable, Comparable<Object> {

	private static final long serialVersionUID = 1L;



  private String valueString;


  private Double weight;


	private Link link;

  public EntityQuestion() {}

  public EntityQuestion(Link link) {
    this.link = link;
  }

	@Override
	public int compareTo(Object o) {
		return 0;
	}
}

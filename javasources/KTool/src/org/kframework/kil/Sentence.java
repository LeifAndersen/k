package org.kframework.kil;

import org.kframework.kil.loader.Constants;
import org.kframework.kil.loader.JavaClassesFactory;
import org.kframework.utils.xml.XML;
import org.w3c.dom.Element;

public abstract class Sentence extends ModuleItem {
	Term body;
	Term condition = null;
	Attributes sentenceAttributes;

	public Sentence(Sentence s) {
		super(s);
		this.body = s.body;
		this.condition = s.condition;
		this.sentenceAttributes = s.sentenceAttributes;
	}

	public Sentence() {
		super();
		sentenceAttributes = new Attributes();
	}

	public Sentence(String location, String filename) {
		super(location, filename);
		sentenceAttributes = new Attributes();
	}

	public Sentence(Element element) {
		super(element);

		Element elm = XML.getChildrenElementsByTagName(element, Constants.BODY).get(0);
		Element elmBody = XML.getChildrenElements(elm).get(0);
		this.body = (Term) JavaClassesFactory.getTerm(elmBody);

		java.util.List<Element> its = XML.getChildrenElementsByTagName(element, Constants.COND);
		if (its.size() > 0)
			this.condition = (Term) JavaClassesFactory.getTerm(XML.getChildrenElements(its.get(0)).get(0));

		its = XML.getChildrenElementsByTagName(element, Constants.ATTRIBUTES);
		// assumption: <cellAttributes> appears only once
		if (its.size() > 0) {
			sentenceAttributes = (Attributes) JavaClassesFactory.getTerm(its.get(0));
		} else
			sentenceAttributes = new Attributes("generated", "generated");
	}

	public Term getBody() {
		return body;
	}

	public void setBody(Term body) {
		this.body = body;
	}

	public Term getCondition() {
		return condition;
	}

	public void setCondition(Term condition) {
		this.condition = condition;
	}

	public Attributes getSentenceAttributes() {
		return sentenceAttributes;
	}

	public void setSentenceAttributes(Attributes sentenceAttributes) {
		this.sentenceAttributes = sentenceAttributes;
	}

	@Override
	public abstract Sentence shallowCopy();
}

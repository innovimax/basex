package org.basex.core;

import org.basex.data.Data;
import org.basex.data.Nodes;

/**
 * This class stores the reference to the currently opened database.
 * Moreover, it provides references to the currently used, marked and
 * copied node sets.
 *
 * @author Workgroup DBIS, University of Konstanz 2005-09, ISC License
 * @author Christian Gruen
 */
public final class Context {
  /** Database pool. */
  private static final DataPool POOL = new DataPool();
  /** Sets the main properties. */
  public final Prop prop;
  /** Central data reference. */
  private Data data;
  /** Current context. */
  private Nodes current;
  /** Currently marked nodes. */
  private Nodes marked;
  /** Currently copied nodes. */
  private Nodes copied;

  /**
   * Constructor.
   */
  public Context() {
    this(new Prop());
  }

  /**
   * Constructor, defining an initial property file.
   * @param p property file
   */
  public Context(final Prop p) {
    prop = p;
  }

  /**
   * Closes the database instance.
   */
  public void close() {
    POOL.close();
    prop.write();
  }

  /**
   * Returns true if all current nodes refer to document nodes.
   * @return result of check
   */
  public boolean root() {
    if(current == null) return true;
    for(final int n : current.nodes) if(data.kind(n) != Data.DOC) return false;
    return true;
  }

  /**
   * Returns data reference.
   * @return data reference
   */
  public Data data() {
    return data;
  }

  /**
   * Sets the specified data instance as current database.
   * @param d data reference
   */
  public synchronized void openDB(final Data d) {
    data = d;
    copied = null;
    marked = new Nodes(d);
    update();
  }

  /**
   * Removes the current database context.
   */
  public synchronized void closeDB() {
    data = null;
    current = null;
    marked = null;
    copied = null;
  }

  /**
   * Updates references to the document nodes.
   */
  public void update() {
    current = new Nodes(data.doc(), data);
  }

  /**
   * Returns the current context set.
   * @return current context set
   */
  public Nodes current() {
    return current;
  }

  /**
   * Sets the current context set.
   * @param curr current context set
   */
  public void current(final Nodes curr) {
    current = curr;
  }

  /**
   * Returns the copied context set.
   * @return copied context set
   */
  public Nodes copied() {
    return copied;
  }

  /**
   * Sets the current node set as copy.
   * @param copy current node set as copy.
   */
  public void copy(final Nodes copy) {
    copied = copy;
  }

  /**
   * Returns the marked context set.
   * @return marked context set
   */
  public Nodes marked() {
    return marked;
  }

  /**
   * Sets the marked context set.
   * @param mark marked context set
   */
  public void marked(final Nodes mark) {
    marked = mark;
  }

  /**
   * Pins the pool.
   * @param name name of database
   * @return data reference
   */
  public Data pin(final String name) {
    return POOL.pin(name);
  }

  /**
   * Unpins a data reference.
   * @param d data reference
   * @return true if reference was removed from the pool
   */
  public boolean unpin(final Data d) {
    return POOL.unpin(d);
  }

  /**
   * Adds the specified data reference to the pool.
   * @param d data reference
   */
  public void addToPool(final Data d) {
    POOL.add(d);
  }

  /**
   * Checks if the specified database is pinned.
   * @param db name of database
   * @return int use-status
   */
  public boolean pinned(final String db) {
    return POOL.pinned(db);
  }
}

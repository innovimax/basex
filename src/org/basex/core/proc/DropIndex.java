package org.basex.core.proc;

import static org.basex.Text.*;
import static org.basex.core.Commands.*;
import static org.basex.data.DataText.*;
import java.io.IOException;
import org.basex.BaseX;
import org.basex.core.Process;
import org.basex.core.Commands.CmdIndex;
import org.basex.data.Data;
import org.basex.data.Data.Type;
import org.basex.io.IO;

/**
 * Evaluates the 'drop index' command.
 *
 * @author Workgroup DBIS, University of Konstanz 2005-09, ISC License
 * @author Christian Gruen
 */
public final class DropIndex extends Process {
  /** Index type. */
  final CmdIndex type;

  /**
   * Constructor.
   * @param t index type
   */
  public DropIndex(final CmdIndex t) {
    super(DATAREF);
    type = t;
  }

  @Override
  protected boolean exec() {
    final Data data = context.data();
    switch(type) {
      case TEXT:
        data.meta.txtindex = false;
        return drop(Type.TXT, DATATXT);
      case ATTRIBUTE:
        data.meta.atvindex = false;
        return drop(Type.ATV, DATAATV);
      case FULLTEXT:
        data.meta.ftxindex = false;
        return drop(Type.FTX, DATAFTX);
      default:
        return false;
    }
  }

  /**
   * Drops the specified index.
   * @param index index type
   * @param pat pattern
   * @return success of operation
   */
  private boolean drop(final Type index, final String pat) {
    try {
      final Data data = context.data();
      data.flush();
      data.closeIndex(index);
      return DropDB.delete(data.meta.name, pat + "." + IO.BASEXSUFFIX, prop) ?
          info(DBDROP, perf.getTimer()) : error(DBDROPERR);
    } catch(final IOException ex) {
      BaseX.debug(ex);
      return error(ex.getMessage());
    }
  }

  @Override
  public String toString() {
    return Cmd.DROP.name() + " " + CmdDrop.INDEX + " " + type;
  }
}

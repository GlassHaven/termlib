/* repro533: drive vterm into lineinfo[0].continuation==1, then resize.
 *
 * A soft-wrapped line writes continuation=1 on its second row; scrolling
 * shifts lineinfo up, so once the head row scrolls off, ROW 0 carries
 * continuation=1. resize_buffer's chain walk then decrements old_row to -1
 * and the reflow copy reads old_buffer[-old_cols] — heap underflow (#533).
 */
#include <stdio.h>
#include <string.h>
#include "vterm.h"

static int cb_damage(VTermRect r, void *u) { (void)r; (void)u; return 1; }
static int cb_movecursor(VTermPos p, VTermPos o, int v, void *u) { (void)p;(void)o;(void)v;(void)u; return 1; }

int main(void) {
  int rows = 5, cols = 20;
  VTerm *vt = vterm_new(rows, cols);
  vterm_set_utf8(vt, 1);
  VTermScreen *screen = vterm_obtain_screen(vt);
  vterm_screen_enable_reflow(screen, true);
  static VTermScreenCallbacks cbs;
  memset(&cbs, 0, sizeof cbs);
  cbs.damage = cb_damage;
  cbs.movecursor = cb_movecursor;
  vterm_screen_set_callbacks(screen, &cbs, NULL);
  vterm_screen_reset(screen, 1);

  /* One long line that soft-wraps across every row (100 chars over 20 cols
   * = 5 rows), then newlines to scroll its head off the top: row 0 is now a
   * continuation row. */
  char line[101];
  memset(line, 'A', 100); line[100] = 0;
  vterm_input_write(vt, line, strlen(line));
  vterm_input_write(vt, "\r\nx\r\ny\r\n", 8);

  fprintf(stderr, "resizing...\n");
  vterm_set_size(vt, rows, 30);   /* wider: reflow walk runs */
  fprintf(stderr, "survived resize 1\n");
  vterm_set_size(vt, rows, 12);   /* narrower */
  fprintf(stderr, "survived resize 2\n");
  vterm_free(vt);

  /* Excess CSI parameters/subparameters, numeric clamp, fresh REP, and
   * resize-invalidated DECSTBM/DECSLRM scroll regions under ASan. */
  vt = vterm_new(10, 40);
  vterm_set_utf8(vt, 1);
  screen = vterm_obtain_screen(vt);
  vterm_screen_set_callbacks(screen, &cbs, NULL);
  vterm_screen_reset(screen, 1);
  vterm_input_write(vt, "\x1b[9b", 4);
  vterm_input_write(vt, "\x1b[;;;;;;;;;;;;;;;;;;;;;m", 24);
  vterm_input_write(vt, "\x1b[:::::::::::::::::::::m", 24);
  vterm_input_write(vt, "\x1b[999999999999999999999999999999999999;3m", 41);
  vterm_input_write(vt, "\x1b]99999999999999999999999999;ok\x07", 32);
  vterm_input_write(vt, "\x1b[5;9r", 6);
  vterm_set_size(vt, 3, 40);
  vterm_input_write(vt, "\x1b[5S\x1b[5T\x1b[3;1HX\nY", 17);
  vterm_free(vt);

  fprintf(stderr, "no crash\n");
  return 0;
}

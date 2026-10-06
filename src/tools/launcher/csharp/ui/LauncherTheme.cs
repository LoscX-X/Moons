using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace Moons.WindowsLauncher
{
    // Desktop equivalents of the website's palette, glass surfaces and white actions.
    internal static class LauncherTheme
    {
        internal static readonly Color Background = Color.FromArgb(11, 11, 13);
        internal static readonly Color Panel = Color.FromArgb(20, 20, 22);
        internal static readonly Color Border = Color.FromArgb(41, 41, 45);
        internal static readonly Color Text = Color.FromArgb(240, 240, 242);
        internal static readonly Color Muted = Color.FromArgb(153, 153, 159);
        internal static readonly Color Soft = Color.FromArgb(116, 116, 124);
        internal static readonly Color White = Color.FromArgb(241, 241, 243);

        internal static GraphicsPath RoundedRectangle(RectangleF bounds, float radius)
        {
            float diameter = Math.Max(1, Math.Min(radius * 2, Math.Min(bounds.Width, bounds.Height)));
            GraphicsPath path = new GraphicsPath();
            path.AddArc(bounds.Left, bounds.Top, diameter, diameter, 180, 90);
            path.AddArc(bounds.Right - diameter, bounds.Top, diameter, diameter, 270, 90);
            path.AddArc(bounds.Right - diameter, bounds.Bottom - diameter, diameter, diameter, 0, 90);
            path.AddArc(bounds.Left, bounds.Bottom - diameter, diameter, diameter, 90, 90);
            path.CloseFigure();
            return path;
        }

        internal static Label Label(Control parent, string text, Rectangle bounds,
            float size, bool bold, Color color)
        {
            Label label = new Label();
            label.Text = text;
            label.Bounds = bounds;
            label.Font = new Font("Segoe UI", size, bold ? FontStyle.Bold : FontStyle.Regular);
            label.ForeColor = color;
            label.BackColor = Color.Transparent;
            parent.Controls.Add(label);
            return label;
        }

        internal static void DrawMoon(Graphics graphics, RectangleF bounds, Color color)
        {
            using (GraphicsPath moon = new GraphicsPath())
            using (GraphicsPath cutout = new GraphicsPath())
            {
                moon.AddEllipse(bounds);
                cutout.AddEllipse(bounds.X + bounds.Width * 0.36F,
                    bounds.Y - bounds.Height * 0.19F, bounds.Width, bounds.Height);
                using (Region crescent = new Region(moon))
                using (SolidBrush fill = new SolidBrush(color))
                {
                    crescent.Exclude(cutout);
                    graphics.FillRegion(fill, crescent);
                }
            }
        }

        internal static void DrawChoice(Graphics graphics, Rectangle bounds, string text,
            Font font, bool selected, bool focused)
        {
            graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using (SolidBrush background = new SolidBrush(Panel))
                graphics.FillRectangle(background, bounds);
            Rectangle row = Rectangle.Inflate(bounds, -4, -2);
            using (GraphicsPath path = RoundedRectangle(row, 7))
            using (SolidBrush fill = new SolidBrush(selected ? Color.FromArgb(40, 40, 44) : Panel))
            using (Pen line = new Pen(selected ? Color.FromArgb(77, 77, 83) : Panel))
            {
                graphics.FillPath(fill, path);
                graphics.DrawPath(line, path);
            }
            DrawMoon(graphics, new RectangleF(bounds.Left + 16, bounds.Top + 12, 16, 16),
                selected ? Text : Muted);
            TextRenderer.DrawText(graphics, text, font,
                new Rectangle(bounds.Left + 46, bounds.Top, bounds.Width - 78, bounds.Height),
                selected ? Text : Muted, TextFormatFlags.EndEllipsis | TextFormatFlags.VerticalCenter);
            if (selected)
            {
                using (Pen pen = new Pen(Text, 1.5F))
                {
                    graphics.DrawLine(pen, bounds.Right - 27, bounds.Top + 20,
                        bounds.Right - 23, bounds.Top + 24);
                    graphics.DrawLine(pen, bounds.Right - 23, bounds.Top + 24,
                        bounds.Right - 16, bounds.Top + 16);
                }
            }
            if (focused) ControlPaint.DrawFocusRectangle(graphics, Rectangle.Inflate(row, -2, -2), Text, Panel);
        }

        internal static void ShowError(IWin32Window owner, string title, string message)
        {
            using (ThemeMessageDialog dialog = new ThemeMessageDialog(title, message))
            {
                if (owner == null) dialog.ShowDialog();
                else dialog.ShowDialog(owner);
            }
        }

        // Render-only verification; callers construct forms without starting workers.
        internal static int SaveSnapshot(Form form, string outputPath)
        {
            using (form)
            {
                string fullPath = Path.GetFullPath(outputPath);
                Directory.CreateDirectory(Path.GetDirectoryName(fullPath));
                form.ShowInTaskbar = false;
                form.StartPosition = FormStartPosition.Manual;
                form.Location = new Point(-32000, -32000);
                form.Show();
                Application.DoEvents();
                using (Bitmap bitmap = new Bitmap(form.Width, form.Height))
                {
                    form.DrawToBitmap(bitmap, new Rectangle(Point.Empty, bitmap.Size));
                    bitmap.Save(fullPath, System.Drawing.Imaging.ImageFormat.Png);
                }
                form.Hide();
            }
            return 0;
        }
    }

    internal class ThemeForm : Form
    {
        private static readonly PointF[] Stars = {
            new PointF(.03F, .21F), new PointF(.13F, .45F), new PointF(.22F, .24F),
            new PointF(.34F, .36F), new PointF(.47F, .09F), new PointF(.60F, .24F),
            new PointF(.74F, .12F), new PointF(.88F, .31F), new PointF(.97F, .18F),
            new PointF(.05F, .78F), new PointF(.19F, .92F), new PointF(.30F, .68F),
            new PointF(.50F, .87F), new PointF(.66F, .66F), new PointF(.81F, .90F),
            new PointF(.95F, .75F), new PointF(.83F, .52F)
        };
        internal double AnimationSeconds = 0.0;
        private readonly string brand;

        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(IntPtr window, int attribute, ref int value, int size);
        [DllImport("user32.dll")]
        private static extern bool ReleaseCapture();
        [DllImport("user32.dll")]
        private static extern IntPtr SendMessage(IntPtr window, int message, IntPtr wParam, IntPtr lParam);

        internal ThemeForm(string brand)
        {
            this.brand = brand;
            BackColor = LauncherTheme.Background;
            ForeColor = LauncherTheme.Text;
            Font = new Font("Segoe UI", 9F);
            FormBorderStyle = FormBorderStyle.None;
            AutoScaleMode = AutoScaleMode.Dpi;
            AutoScaleDimensions = new SizeF(96, 96);
            ShowIcon = false;
            ControlBox = MaximizeBox = MinimizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            DoubleBuffered = true;
            EnableDrag(this);
        }

        internal void EnableDrag(Control surface)
        {
            surface.MouseDown += delegate(object sender, MouseEventArgs e) {
                if (e.Button != MouseButtons.Left) return;
                ReleaseCapture();
                SendMessage(Handle, 0x00A1, new IntPtr(2), IntPtr.Zero);
            };
        }

        internal void InstallChrome(bool minimize, string section)
        {
            Label name = LauncherTheme.Label(this, brand.ToLowerInvariant() + ".",
                new Rectangle(58, 24, 140, 30), 15F, true, LauncherTheme.Text);
            Label category = LauncherTheme.Label(this, section,
                new Rectangle(191, 32, 210, 20), 7.5F, false, LauncherTheme.Muted);
            EnableDrag(name);
            EnableDrag(category);
            ThemeChromeButton close = new ThemeChromeButton(true);
            close.SetBounds(ClientSize.Width - 50, 21, 30, 30);
            close.Anchor = AnchorStyles.Top | AnchorStyles.Right;
            close.Click += delegate { Close(); };
            Controls.Add(close);
            if (minimize)
            {
                ThemeChromeButton small = new ThemeChromeButton(false);
                small.SetBounds(ClientSize.Width - 86, 21, 30, 30);
                small.Anchor = AnchorStyles.Top | AnchorStyles.Right;
                small.Click += delegate { WindowState = FormWindowState.Minimized; };
                Controls.Add(small);
            }
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            try { int round = 2; DwmSetWindowAttribute(Handle, 33, ref round, sizeof(int)); }
            catch (DllNotFoundException) { }
            catch (EntryPointNotFoundException) { }
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            PointF[] positions = new PointF[Stars.Length];
            for (int i = 0; i < Stars.Length; i++)
                positions[i] = new PointF(Stars[i].X * ClientSize.Width, Stars[i].Y * ClientSize.Height);
            using (Pen connection = new Pen(Color.FromArgb(13, LauncherTheme.Text)))
            using (SolidBrush star = new SolidBrush(LauncherTheme.Text))
            {
                for (int i = 0; i < positions.Length; i++)
                {
                    for (int j = i + 1; j < positions.Length; j++)
                    {
                        double dx = Stars[i].X - Stars[j].X, dy = Stars[i].Y - Stars[j].Y;
                        if (dx * dx + dy * dy < .055) g.DrawLine(connection, positions[i], positions[j]);
                    }
                    star.Color = Color.FromArgb(32 + (int)(20 * (1 + Math.Sin(AnimationSeconds * .7 + i))),
                        LauncherTheme.Text);
                    g.FillEllipse(star, positions[i].X - 1, positions[i].Y - 1, 2, 2);
                }
            }
            LauncherTheme.DrawMoon(g, new RectangleF(32, 30, 17, 17), LauncherTheme.White);
            using (Pen line = new Pen(LauncherTheme.Border))
            {
                g.DrawLine(line, 32, 72, ClientSize.Width - 32, 72);
                using (GraphicsPath path = LauncherTheme.RoundedRectangle(
                    new Rectangle(0, 0, ClientSize.Width - 1, ClientSize.Height - 1), 15))
                    g.DrawPath(line, path);
            }
        }
    }

    internal sealed class GlassPanel : Panel
    {
        internal GlassPanel()
        {
            DoubleBuffered = true;
            SetStyle(ControlStyles.SupportsTransparentBackColor, true);
            BackColor = Color.Transparent;
        }
        protected override void OnPaintBackground(PaintEventArgs e)
        {
            base.OnPaintBackground(e);
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            Rectangle bounds = new Rectangle(0, 0, Width - 1, Height - 1);
            using (GraphicsPath path = LauncherTheme.RoundedRectangle(bounds, 12))
            using (LinearGradientBrush fill = new LinearGradientBrush(bounds,
                Color.FromArgb(242, 28, 28, 31), Color.FromArgb(246, 17, 17, 19), 110F))
            using (Pen outline = new Pen(Color.FromArgb(64, 112, 112, 121)))
            {
                e.Graphics.FillPath(fill, path);
                e.Graphics.DrawPath(outline, path);
            }
            using (Pen reflection = new Pen(Color.FromArgb(32, Color.White)))
                e.Graphics.DrawLine(reflection, 14, 1, Width / 2, 1);
        }
    }

    internal class ThemeButton : Button
    {
        internal bool Primary;
        protected bool Hovered, Pressed;
        internal ThemeButton(bool primary)
        {
            Primary = primary;
            FlatStyle = FlatStyle.Flat;
            FlatAppearance.BorderSize = 0;
            UseVisualStyleBackColor = false;
            BackColor = LauncherTheme.Panel;
            ForeColor = LauncherTheme.Text;
            Font = new Font("Segoe UI", 9F, FontStyle.Bold);
            Cursor = Cursors.Hand;
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint
                | ControlStyles.OptimizedDoubleBuffer, true);
        }
        protected override void OnMouseEnter(EventArgs e) { Hovered = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { Hovered = Pressed = false; Invalidate(); base.OnMouseLeave(e); }
        protected override void OnMouseDown(MouseEventArgs e) { Pressed = e.Button == MouseButtons.Left; Invalidate(); base.OnMouseDown(e); }
        protected override void OnMouseUp(MouseEventArgs e) { Pressed = false; Invalidate(); base.OnMouseUp(e); }
        protected override void OnKeyDown(KeyEventArgs e) { if (e.KeyCode == Keys.Space) { Pressed = true; Invalidate(); } base.OnKeyDown(e); }
        protected override void OnKeyUp(KeyEventArgs e) { Pressed = false; Invalidate(); base.OnKeyUp(e); }
        protected override void OnEnabledChanged(EventArgs e) { Hovered = Pressed = false; Invalidate(); base.OnEnabledChanged(e); }
        protected Color DrawSurface(Graphics graphics)
        {
            graphics.SmoothingMode = SmoothingMode.AntiAlias;
            Color fill = !Enabled ? LauncherTheme.Panel : Primary
                ? Pressed ? Color.FromArgb(211, 211, 216) : Hovered ? Color.White : LauncherTheme.White
                : Pressed ? Color.FromArgb(40, 40, 45) : Hovered ? Color.FromArgb(31, 31, 35) : LauncherTheme.Panel;
            Color color = !Enabled ? LauncherTheme.Soft : Primary ? LauncherTheme.Panel : LauncherTheme.Text;
            using (GraphicsPath path = LauncherTheme.RoundedRectangle(new Rectangle(0, 0, Width - 1, Height - 1), 7))
            using (SolidBrush brush = new SolidBrush(fill))
            using (Pen line = new Pen(Primary && Enabled ? fill : Color.FromArgb(58, 58, 63)))
            {
                graphics.FillPath(brush, path);
                graphics.DrawPath(line, path);
            }
            return color;
        }
        protected override void OnPaint(PaintEventArgs e)
        {
            Color color = DrawSurface(e.Graphics);
            TextRenderer.DrawText(e.Graphics, Text, Font, ClientRectangle, color,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
            if (Focused && ShowFocusCues) ControlPaint.DrawFocusRectangle(e.Graphics,
                Rectangle.Inflate(ClientRectangle, -4, -4), color, BackColor);
        }
    }

    internal sealed class ThemeChromeButton : ThemeButton
    {
        private readonly bool close;
        internal ThemeChromeButton(bool close) : base(false)
        {
            this.close = close;
            SetStyle(ControlStyles.SupportsTransparentBackColor, true);
            BackColor = Color.Transparent;
            AccessibleName = close ? "Close" : "Minimize";
            TabStop = false;
        }
        protected override void OnPaint(PaintEventArgs e)
        {
            if (Hovered || Pressed) base.OnPaint(e);
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using (Pen pen = new Pen(Hovered ? LauncherTheme.Text : LauncherTheme.Muted, 1.3F))
            {
                if (close) { e.Graphics.DrawLine(pen, 11, 11, 19, 19); e.Graphics.DrawLine(pen, 19, 11, 11, 19); }
                else e.Graphics.DrawLine(pen, 11, 17, 19, 17);
            }
        }
    }

    internal sealed class ThemeProgressBar : Control
    {
        private double value;
        internal double Value
        {
            get { return value; }
            set { this.value = Math.Max(0, Math.Min(100, value)); Invalidate(); }
        }
        internal ThemeProgressBar()
        {
            DoubleBuffered = true;
            Height = 6;
            BackColor = LauncherTheme.Panel;
            AccessibleRole = AccessibleRole.ProgressBar;
            AccessibleName = "Progress";
        }
        protected override void OnPaint(PaintEventArgs e)
        {
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            Rectangle bounds = new Rectangle(0, 0, Width - 1, Height - 1);
            using (GraphicsPath path = LauncherTheme.RoundedRectangle(bounds, Height / 2F))
            using (SolidBrush track = new SolidBrush(Color.FromArgb(47, 47, 52)))
                e.Graphics.FillPath(track, path);
            int width = (int)Math.Round((Width - 1) * value / 100);
            if (width < 1) return;
            using (GraphicsPath path = LauncherTheme.RoundedRectangle(new Rectangle(0, 0, width, Height - 1), Height / 2F))
            using (SolidBrush fill = new SolidBrush(LauncherTheme.White))
                e.Graphics.FillPath(fill, path);
        }
    }

    internal sealed class ThemeVersionSelector : ThemeButton
    {
        internal readonly List<string> Items = new List<string>();
        private int selectedIndex = -1;
        private ContextMenuStrip menu;

        internal int SelectedIndex
        {
            get { return selectedIndex; }
            set {
                if (value < -1 || value >= Items.Count) throw new ArgumentOutOfRangeException("value");
                selectedIndex = value;
                Text = value < 0 ? "Choose Minecraft" : Items[value];
                AccessibleDescription = Text;
                Invalidate();
            }
        }

        internal ThemeVersionSelector() : base(false)
        {
            Font = new Font("Segoe UI", 10F);
            AccessibleRole = AccessibleRole.ComboBox;
            AccessibleName = "Minecraft version";
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Color color = DrawSurface(e.Graphics);
            TextRenderer.DrawText(e.Graphics, Text, Font, new Rectangle(14, 0, Width - 52, Height),
                color, TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
            float middle = Height / 2F;
            bool opened = menu != null && menu.Visible;
            using (Pen arrow = new Pen(Enabled ? LauncherTheme.Muted : LauncherTheme.Soft, 1.3F))
            {
                arrow.StartCap = arrow.EndCap = LineCap.Round;
                float direction = opened ? -1 : 1;
                e.Graphics.DrawLines(arrow, new[] {
                    new PointF(Width - 27, middle - 2 * direction),
                    new PointF(Width - 23, middle + 2 * direction),
                    new PointF(Width - 19, middle - 2 * direction)
                });
            }
            if (Focused && ShowFocusCues)
                using (GraphicsPath path = LauncherTheme.RoundedRectangle(new Rectangle(2, 2, Width - 5, Height - 5), 5))
                using (Pen focus = new Pen(LauncherTheme.Muted)) e.Graphics.DrawPath(focus, path);
        }

        private void PrepareMenu()
        {
            if (menu != null) menu.Dispose();
            menu = new ContextMenuStrip();
            menu.Renderer = new ThemeMenuRenderer();
            menu.ShowImageMargin = false;
            menu.ShowCheckMargin = true;
            menu.BackColor = LauncherTheme.Panel;
            menu.ForeColor = LauncherTheme.Text;
            menu.Font = Font;
            menu.Padding = new Padding(4);
            menu.AutoSize = false;
            menu.Size = new Size(Width, Items.Count * 40 + 8);
            for (int i = 0; i < Items.Count; i++)
            {
                int index = i;
                ToolStripMenuItem item = new ToolStripMenuItem(Items[i]);
                item.Checked = i == selectedIndex;
                item.AutoSize = false;
                item.Size = new Size(Width - 8, 40);
                item.Click += delegate { SelectedIndex = index; };
                menu.Items.Add(item);
            }
            using (GraphicsPath path = LauncherTheme.RoundedRectangle(new Rectangle(0, 0, menu.Width, menu.Height), 8))
                menu.Region = new Region(path);
            menu.Closed += delegate { Focus(); Invalidate(); };
        }

        private void ShowMenu()
        {
            if (!Enabled || Items.Count == 0) return;
            PrepareMenu();
            menu.Show(this, new Point(0, Height + 6));
            if (selectedIndex >= 0) menu.Items[selectedIndex].Select();
            Invalidate();
        }

        protected override void OnClick(EventArgs e) { base.OnClick(e); ShowMenu(); }

        protected override void OnKeyDown(KeyEventArgs e)
        {
            if (Items.Count == 0) { base.OnKeyDown(e); return; }
            if (e.KeyCode == Keys.F4 || (e.Alt && e.KeyCode == Keys.Down)) ShowMenu();
            else if (e.KeyCode == Keys.Up || e.KeyCode == Keys.Down)
                SelectedIndex = Math.Max(0, Math.Min(Items.Count - 1,
                    selectedIndex + (e.KeyCode == Keys.Up ? -1 : 1)));
            else if (e.KeyCode == Keys.Home) SelectedIndex = 0;
            else if (e.KeyCode == Keys.End) SelectedIndex = Items.Count - 1;
            else { base.OnKeyDown(e); return; }
            e.Handled = e.SuppressKeyPress = true;
        }

        // Renders the same popup used by mouse and keyboard selection, without input.
        internal int SaveMenuSnapshot(string outputPath)
        {
            PrepareMenu();
            menu.CreateControl();
            menu.PerformLayout();
            if (selectedIndex >= 0) menu.Items[selectedIndex].Select();
            using (Bitmap bitmap = new Bitmap(menu.Width, menu.Height))
            {
                menu.DrawToBitmap(bitmap, new Rectangle(Point.Empty, bitmap.Size));
                bitmap.Save(outputPath, System.Drawing.Imaging.ImageFormat.Png);
            }
            return 0;
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing && menu != null) menu.Dispose();
            base.Dispose(disposing);
        }
    }

    internal sealed class ThemeMenuRenderer : ToolStripRenderer
    {
        protected override void OnRenderToolStripBackground(ToolStripRenderEventArgs e)
        {
            e.Graphics.Clear(LauncherTheme.Panel);
        }
        protected override void OnRenderToolStripBorder(ToolStripRenderEventArgs e)
        {
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using (GraphicsPath path = LauncherTheme.RoundedRectangle(
                new Rectangle(0, 0, e.ToolStrip.Width - 1, e.ToolStrip.Height - 1), 8))
            using (Pen line = new Pen(Color.FromArgb(58, 58, 63))) e.Graphics.DrawPath(line, path);
        }
        protected override void OnRenderMenuItemBackground(ToolStripItemRenderEventArgs e)
        {
            ToolStripMenuItem item = e.Item as ToolStripMenuItem;
            if (!e.Item.Selected && (item == null || !item.Checked)) return;
            Rectangle bounds = new Rectangle(0, 0, e.Item.Width - 1, e.Item.Height - 1);
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using (GraphicsPath path = LauncherTheme.RoundedRectangle(bounds, 5))
            using (SolidBrush fill = new SolidBrush(e.Item.Selected ? Color.FromArgb(40, 40, 44) : Color.FromArgb(29, 29, 33)))
                e.Graphics.FillPath(fill, path);
        }
        protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e)
        {
            e.TextColor = e.Item.Enabled ? LauncherTheme.Text : LauncherTheme.Soft;
            base.OnRenderItemText(e);
        }
        protected override void OnRenderItemCheck(ToolStripItemImageRenderEventArgs e)
        {
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            Rectangle mark = e.ImageRectangle;
            using (Pen pen = new Pen(LauncherTheme.Text, 1.5F))
            {
                pen.StartCap = pen.EndCap = LineCap.Round;
                e.Graphics.DrawLines(pen, new[] { new Point(mark.Left + 2, mark.Top + 8),
                    new Point(mark.Left + 6, mark.Top + 12), new Point(mark.Left + 13, mark.Top + 4) });
            }
        }
    }

    internal sealed class ThemeMessageDialog : ThemeForm
    {
        internal ThemeMessageDialog(string title, string message) : base("Moons")
        {
            Text = title + " — Moons";
            ClientSize = new Size(600, 390);
            StartPosition = FormStartPosition.CenterParent;
            InstallChrome(false, "CLIENT / NOTICE");
            LauncherTheme.Label(this, title, new Rectangle(32, 94, 536, 42), 22F, true, LauncherTheme.Text);
            GlassPanel card = new GlassPanel();
            card.SetBounds(32, 150, 536, 154);
            Controls.Add(card);
            RichTextBox content = new RichTextBox();
            content.SetBounds(18, 18, 500, 96);
            content.Text = message;
            content.Multiline = content.ReadOnly = true;
            content.ScrollBars = RichTextBoxScrollBars.None;
            content.DetectUrls = false;
            content.BorderStyle = BorderStyle.None;
            content.BackColor = LauncherTheme.Panel;
            content.ForeColor = LauncherTheme.Muted;
            content.Font = Font;
            card.Controls.Add(content);
            LauncherTheme.Label(card, "Select text to copy. Scroll for more.",
                new Rectangle(18, 126, 500, 18), 7.5F, false, LauncherTheme.Soft);
            ThemeButton close = new ThemeButton(true);
            close.Text = "Close";
            close.SetBounds(452, 324, 116, 38);
            close.DialogResult = DialogResult.OK;
            Controls.Add(close);
            AcceptButton = CancelButton = close;
            Shown += delegate { content.Select(0, 0); close.Focus(); };
        }
    }
}

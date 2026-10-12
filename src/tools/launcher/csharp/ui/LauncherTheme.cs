using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace Moons.WindowsLauncher
{
    // Shared desktop controls for the installer, loader and their dialogs.
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
            label.Font = new Font("Segoe UI", size, bold ? FontStyle.Bold : FontStyle.Regular);
            label.ForeColor = color;
            label.BackColor = parent.BackColor;
            label.UseMnemonic = false;
            label.AutoEllipsis = true;
            label.Bounds = bounds;
            parent.Controls.Add(label);
            return label;
        }

        internal static float Scale(Graphics graphics) { return graphics.DpiX / 96F; }

        internal static readonly TextFormatFlags TextFlags = TextFormatFlags.NoPrefix
            | TextFormatFlags.PreserveGraphicsClipping | TextFormatFlags.PreserveGraphicsTranslateTransform;

        internal static void DrawChoice(Graphics graphics, Rectangle bounds, string text,
            Font font, bool selected, bool focused)
        {
            float scale = Scale(graphics);
            graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using (SolidBrush background = new SolidBrush(Panel))
                graphics.FillRectangle(background, bounds);
            Rectangle row = Rectangle.Inflate(bounds, -(int)(4 * scale), -(int)(2 * scale));
            using (GraphicsPath path = RoundedRectangle(row, 6 * scale))
            using (SolidBrush fill = new SolidBrush(selected ? Color.FromArgb(40, 40, 44) : Panel))
            using (Pen line = new Pen(selected ? Color.FromArgb(77, 77, 83) : Panel))
            {
                graphics.FillPath(fill, path);
                graphics.DrawPath(line, path);
            }
            TextRenderer.DrawText(graphics, text, font,
                new Rectangle(bounds.Left + (int)(14 * scale), bounds.Top, bounds.Width - (int)(54 * scale), bounds.Height),
                selected ? Text : Muted, TextFlags | TextFormatFlags.EndEllipsis | TextFormatFlags.VerticalCenter);
            if (selected)
            {
                using (Pen pen = new Pen(Text, 1.5F * scale))
                {
                    float middle = bounds.Top + bounds.Height / 2F;
                    graphics.DrawLine(pen, bounds.Right - 27 * scale, middle,
                        bounds.Right - 23 * scale, middle + 4 * scale);
                    graphics.DrawLine(pen, bounds.Right - 23 * scale, middle + 4 * scale,
                        bounds.Right - 16 * scale, middle - 4 * scale);
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
                SettleProgress(form);
                using (Bitmap bitmap = new Bitmap(form.Width, form.Height))
                {
                    form.DrawToBitmap(bitmap, new Rectangle(Point.Empty, bitmap.Size));
                    bitmap.Save(fullPath, System.Drawing.Imaging.ImageFormat.Png);
                }
                form.Hide();
            }
            return 0;
        }

        private static void SettleProgress(Control parent)
        {
            foreach (Control child in parent.Controls)
            {
                ThemeProgressBar progress = child as ThemeProgressBar;
                if (progress != null) progress.Settle();
                if (child.HasChildren) SettleProgress(child);
            }
        }
    }

    internal class ThemeForm : Form
    {
        private readonly ThemeAnimationClock animation;
        private readonly string brand;

        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(IntPtr window, int attribute, ref int value, int size);
        [DllImport("user32.dll")]
        private static extern bool ReleaseCapture();
        [DllImport("user32.dll")]
        private static extern IntPtr SendMessage(IntPtr window, int message, IntPtr wParam, IntPtr lParam);

        internal ThemeForm(string brand)
        {
            SuspendLayout();
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
            animation = new ThemeAnimationClock(this, Animate);
            EnableDrag(this);
        }

        private void Animate(double deltaSeconds)
        {
            AnimateProgress(this, deltaSeconds);
            if (!HasAnimation(this)) animation.Pause();
        }

        internal void UpdateAnimationState()
        {
            if (animation == null) return;
            if (HasAnimation(this)) animation.UpdateState();
            else animation.Pause();
        }

        private static bool HasAnimation(Control parent)
        {
            foreach (Control child in parent.Controls)
            {
                ThemeProgressBar progress = child as ThemeProgressBar;
                if (progress != null && progress.IsAnimating) return true;
                if (child.HasChildren && HasAnimation(child)) return true;
            }
            return false;
        }

        private static void AnimateProgress(Control parent, double deltaSeconds)
        {
            foreach (Control child in parent.Controls)
            {
                ThemeProgressBar progress = child as ThemeProgressBar;
                if (progress != null) progress.Animate(deltaSeconds);
                if (child.HasChildren) AnimateProgress(child, deltaSeconds);
            }
        }

        protected override void OnLoad(EventArgs e)
        {
            // All derived controls use 96-DPI design coordinates. Scale the
            // completed layout together, not midway through construction.
            ResumeLayout(false);
            PerformAutoScale();
            base.OnLoad(e);
        }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            UpdateAnimationState();
        }

        protected override void OnVisibleChanged(EventArgs e)
        {
            base.OnVisibleChanged(e);
            UpdateAnimationState();
        }

        protected override void OnLocationChanged(EventArgs e)
        {
            base.OnLocationChanged(e);
            UpdateAnimationState();
        }

        protected override void OnResize(EventArgs e)
        {
            base.OnResize(e);
            UpdateAnimationState();
        }

        protected override void OnHandleDestroyed(EventArgs e)
        {
            if (animation != null) animation.Pause();
            base.OnHandleDestroyed(e);
        }

        protected override void WndProc(ref Message message)
        {
            base.WndProc(ref message);
            if (message.Msg == 0x007E) UpdateAnimationState(); // WM_DISPLAYCHANGE
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                if (animation != null) animation.Dispose();
            }
            base.Dispose(disposing);
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
            Label name = LauncherTheme.Label(this, brand + " — " + section,
                new Rectangle(24, 22, 430, 25), 10F, false, LauncherTheme.Text);
            EnableDrag(name);
            ThemeChromeButton close = new ThemeChromeButton(true);
            close.SetBounds(ClientSize.Width - 54, 17, 30, 30);
            close.Anchor = AnchorStyles.Top | AnchorStyles.Right;
            close.Click += delegate { Close(); };
            Controls.Add(close);
            if (minimize)
            {
                ThemeChromeButton small = new ThemeChromeButton(false);
                small.SetBounds(ClientSize.Width - 90, 17, 30, 30);
                small.Anchor = AnchorStyles.Top | AnchorStyles.Right;
                small.Click += delegate { WindowState = FormWindowState.Minimized; };
                Controls.Add(small);
            }
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            UpdateAnimationState();
            try { int round = 2; DwmSetWindowAttribute(Handle, 33, ref round, sizeof(int)); }
            catch (DllNotFoundException) { }
            catch (EntryPointNotFoundException) { }
        }

        protected override void OnPaintBackground(PaintEventArgs e)
        {
            using (SolidBrush background = new SolidBrush(BackColor))
                e.Graphics.FillRectangle(background, ClientRectangle);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            if (ClientSize.Width < 2 || ClientSize.Height < 2) return;
            Graphics g = e.Graphics;
            float scale = LauncherTheme.Scale(g);
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using (Pen line = new Pen(LauncherTheme.Border))
            {
                g.DrawLine(line, 24 * scale, 62 * scale, ClientSize.Width - 24 * scale, 62 * scale);
                using (GraphicsPath path = LauncherTheme.RoundedRectangle(
                    new Rectangle(0, 0, ClientSize.Width - 1, ClientSize.Height - 1), 12 * scale))
                    g.DrawPath(line, path);
            }
        }
    }

    internal sealed class GlassPanel : Panel
    {
        internal GlassPanel()
        {
            DoubleBuffered = true;
            BackColor = LauncherTheme.Panel;
        }
        protected override void OnPaintBackground(PaintEventArgs e)
        {
            using (SolidBrush background = new SolidBrush(Parent == null ? LauncherTheme.Background : Parent.BackColor))
                e.Graphics.FillRectangle(background, ClientRectangle);
            if (Width < 2 || Height < 2) return;
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            Rectangle bounds = new Rectangle(0, 0, Width - 1, Height - 1);
            using (GraphicsPath path = LauncherTheme.RoundedRectangle(bounds, 8 * LauncherTheme.Scale(e.Graphics)))
            using (SolidBrush fill = new SolidBrush(BackColor))
            using (Pen outline = new Pen(LauncherTheme.Border))
            {
                e.Graphics.FillPath(fill, path);
                e.Graphics.DrawPath(outline, path);
            }
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
            Font = new Font("Segoe UI", 9F);
            UseMnemonic = false;
            Cursor = Cursors.Hand;
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint
                | ControlStyles.OptimizedDoubleBuffer, true);
        }
        protected override void OnPaintBackground(PaintEventArgs e)
        {
            PaintSurfaceBackground(e.Graphics);
        }
        protected void PaintSurfaceBackground(Graphics graphics)
        {
            // ButtonBase can skip OnPaintBackground. Always initialize every
            // pixel of its reused double buffer, including the rounded corners.
            using (SolidBrush background = new SolidBrush(Parent == null ? LauncherTheme.Background : Parent.BackColor))
                graphics.FillRectangle(background, ClientRectangle);
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
            PaintSurfaceBackground(graphics);
            graphics.SmoothingMode = SmoothingMode.AntiAlias;
            Color fill = !Enabled ? LauncherTheme.Panel : Primary
                ? Pressed ? Color.FromArgb(211, 211, 216) : Hovered ? Color.White : LauncherTheme.White
                : Pressed ? Color.FromArgb(40, 40, 45) : Hovered ? Color.FromArgb(31, 31, 35) : LauncherTheme.Panel;
            Color color = !Enabled ? LauncherTheme.Soft : Primary ? LauncherTheme.Panel : LauncherTheme.Text;
            using (GraphicsPath path = LauncherTheme.RoundedRectangle(new Rectangle(0, 0, Width - 1, Height - 1), 6 * LauncherTheme.Scale(graphics)))
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
                LauncherTheme.TextFlags | TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
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
            BackColor = LauncherTheme.Background;
            AccessibleName = close ? "Close" : "Minimize";
            TabStop = false;
        }
        protected override void OnPaint(PaintEventArgs e)
        {
            if (Hovered || Pressed) DrawSurface(e.Graphics);
            else PaintSurfaceBackground(e.Graphics);
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            float scale = LauncherTheme.Scale(e.Graphics);
            float middleX = Width / 2F, middleY = Height / 2F, offset = 4 * scale;
            using (Pen pen = new Pen(Hovered ? LauncherTheme.Text : LauncherTheme.Muted, 1.3F * scale))
            {
                if (close) { e.Graphics.DrawLine(pen, middleX - offset, middleY - offset, middleX + offset, middleY + offset);
                    e.Graphics.DrawLine(pen, middleX + offset, middleY - offset, middleX - offset, middleY + offset); }
                else e.Graphics.DrawLine(pen, middleX - offset, middleY, middleX + offset, middleY);
            }
        }
    }

    internal sealed class ThemeProgressBar : Control
    {
        private double value, displayedValue;
        internal double Value
        {
            get { return value; }
            set {
                double next = Math.Max(0, Math.Min(100, value));
                if (next < this.value) displayedValue = next; // Retry starts at zero.
                this.value = next;
                Invalidate();
                ThemeForm form = FindForm() as ThemeForm;
                if (form != null) form.UpdateAnimationState();
            }
        }
        internal bool IsAnimating { get { return displayedValue != value; } }
        internal void Animate(double deltaSeconds)
        {
            if (displayedValue == value) return;
            displayedValue += (value - displayedValue) * (1.0 - Math.Exp(-14.0 * deltaSeconds));
            if (Math.Abs(value - displayedValue) < .05) displayedValue = value;
            Invalidate();
            Update();
        }
        internal void Settle() { displayedValue = value; Invalidate(); }
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
            if (Width < 2 || Height < 2) return;
            using (SolidBrush background = new SolidBrush(BackColor))
                e.Graphics.FillRectangle(background, ClientRectangle);
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            Rectangle bounds = new Rectangle(0, 0, Width - 1, Height - 1);
            using (GraphicsPath path = LauncherTheme.RoundedRectangle(bounds, Height / 2F))
            using (SolidBrush track = new SolidBrush(Color.FromArgb(47, 47, 52)))
                e.Graphics.FillPath(track, path);
            float width = (float)((Width - 1) * displayedValue / 100);
            if (width < 1) return;
            using (GraphicsPath path = LauncherTheme.RoundedRectangle(new RectangleF(0, 0, width, Height - 1), Height / 2F))
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
            float scale = LauncherTheme.Scale(e.Graphics);
            TextRenderer.DrawText(e.Graphics, Text, Font, new Rectangle((int)(14 * scale), 0, Width - (int)(52 * scale), Height),
                color, LauncherTheme.TextFlags | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
            float middle = Height / 2F;
            bool opened = menu != null && menu.Visible;
            using (Pen arrow = new Pen(Enabled ? LauncherTheme.Muted : LauncherTheme.Soft, 1.3F * scale))
            {
                arrow.StartCap = arrow.EndCap = LineCap.Round;
                float direction = opened ? -1 : 1;
                e.Graphics.DrawLines(arrow, new[] {
                    new PointF(Width - 27 * scale, middle - 2 * scale * direction),
                    new PointF(Width - 23 * scale, middle + 2 * scale * direction),
                    new PointF(Width - 19 * scale, middle - 2 * scale * direction)
                });
            }
            if (Focused && ShowFocusCues)
                using (GraphicsPath path = LauncherTheme.RoundedRectangle(new Rectangle(2, 2, Width - 5, Height - 5), 5))
                using (Pen focus = new Pen(LauncherTheme.Muted)) e.Graphics.DrawPath(focus, path);
        }

        private void PrepareMenu()
        {
            if (menu != null) menu.Dispose();
            float scale;
            using (Graphics graphics = CreateGraphics()) scale = LauncherTheme.Scale(graphics);
            int padding = (int)Math.Round(4 * scale), rowHeight = (int)Math.Round(40 * scale);
            menu = new ContextMenuStrip();
            menu.Renderer = new ThemeMenuRenderer();
            menu.ShowImageMargin = false;
            menu.ShowCheckMargin = true;
            menu.BackColor = LauncherTheme.Panel;
            menu.ForeColor = LauncherTheme.Text;
            menu.Font = Font;
            menu.Padding = new Padding(padding);
            menu.AutoSize = false;
            menu.Size = new Size(Width, Items.Count * rowHeight + padding * 2);
            for (int i = 0; i < Items.Count; i++)
            {
                int index = i;
                ToolStripMenuItem item = new ToolStripMenuItem(Items[i]);
                item.Checked = i == selectedIndex;
                item.AutoSize = false;
                item.Size = new Size(Width - padding * 2, rowHeight);
                item.Click += delegate { SelectedIndex = index; };
                menu.Items.Add(item);
            }
            using (GraphicsPath path = LauncherTheme.RoundedRectangle(new Rectangle(0, 0, menu.Width, menu.Height), 6 * scale))
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
                new Rectangle(0, 0, e.ToolStrip.Width - 1, e.ToolStrip.Height - 1), 6 * LauncherTheme.Scale(e.Graphics)))
            using (Pen line = new Pen(Color.FromArgb(58, 58, 63))) e.Graphics.DrawPath(line, path);
        }
        protected override void OnRenderMenuItemBackground(ToolStripItemRenderEventArgs e)
        {
            ToolStripMenuItem item = e.Item as ToolStripMenuItem;
            if (!e.Item.Selected && (item == null || !item.Checked)) return;
            Rectangle bounds = new Rectangle(0, 0, e.Item.Width - 1, e.Item.Height - 1);
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using (GraphicsPath path = LauncherTheme.RoundedRectangle(bounds, 5 * LauncherTheme.Scale(e.Graphics)))
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
            float scale = LauncherTheme.Scale(e.Graphics);
            float x = mark.Left + mark.Width / 2F, y = mark.Top + mark.Height / 2F;
            using (Pen pen = new Pen(LauncherTheme.Text, 1.5F * scale))
            {
                pen.StartCap = pen.EndCap = LineCap.Round;
                e.Graphics.DrawLines(pen, new[] { new PointF(x - 5 * scale, y),
                    new PointF(x - scale, y + 4 * scale), new PointF(x + 6 * scale, y - 4 * scale) });
            }
        }
    }

    internal sealed class ThemeMessageDialog : ThemeForm
    {
        internal ThemeMessageDialog(string title, string message) : base("Moons")
        {
            Text = title + " — Moons";
            ClientSize = new Size(600, 336);
            StartPosition = FormStartPosition.CenterParent;
            InstallChrome(false, "Notice");
            LauncherTheme.Label(this, title, new Rectangle(24, 82, 552, 30), 14F, false, LauncherTheme.Text);
            GlassPanel card = new GlassPanel();
            card.SetBounds(24, 124, 552, 138);
            card.Padding = new Padding(16, 14, 16, 38);
            Controls.Add(card);
            RichTextBox content = new RichTextBox();
            content.Dock = DockStyle.Fill;
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
                new Rectangle(16, 111, 520, 18), 8F, false, LauncherTheme.Soft);
            ThemeButton close = new ThemeButton(true);
            close.Text = "Close";
            close.SetBounds(460, 280, 116, 36);
            close.DialogResult = DialogResult.OK;
            Controls.Add(close);
            AcceptButton = CancelButton = close;
            Shown += delegate { content.Select(0, 0); close.Focus(); };
        }
    }
}

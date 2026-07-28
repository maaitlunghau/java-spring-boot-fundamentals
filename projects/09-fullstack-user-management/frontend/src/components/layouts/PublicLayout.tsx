import { Link, Outlet } from 'react-router-dom'
import { useTheme, type Theme } from '../../context/ThemeProvider'
import { useAuth } from '../../context/AuthProvider'
import { Button } from '../ui/Button'
import { Avatar } from '../ui/Avatar'
import { IconMoon, IconSun, IconMonitor } from '../icons'
import { cn } from '../../lib/utils'

function ThemeToggle() {
  const { theme, setTheme } = useTheme()
  const themes: { key: Theme; icon: typeof IconSun; label: string }[] = [
    { key: 'light', icon: IconSun, label: 'Sáng' },
    { key: 'dark', icon: IconMoon, label: 'Tối' },
    { key: 'system', icon: IconMonitor, label: 'Hệ thống' },
  ]
  return (
    <div className="flex items-center rounded-full border border-neutral-200 dark:border-neutral-700 bg-neutral-100 dark:bg-neutral-800 p-0.5 gap-0.5">
      {themes.map(({ key, icon: Icon, label }) => (
        <button
          key={key}
          onClick={() => setTheme(key)}
          aria-label={label}
          className={cn(
            'flex h-7 w-7 items-center justify-center rounded-full transition-colors',
            theme === key
              ? 'bg-white dark:bg-neutral-700 text-neutral-900 dark:text-neutral-100 shadow-sm'
              : 'text-neutral-500 hover:text-neutral-700 dark:text-neutral-400 dark:hover:text-neutral-200',
          )}
        >
          <Icon size={14} />
        </button>
      ))}
    </div>
  )
}

export function PublicLayout() {
  const { user } = useAuth()

  return (
    <div className="min-h-screen flex flex-col bg-white dark:bg-neutral-950">
      <header className="sticky top-0 z-30 border-b border-neutral-200 dark:border-neutral-800 bg-white/80 dark:bg-neutral-950/80 backdrop-blur-sm">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3">
          <Link to="/" className="flex items-center gap-2">
            <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-primary-600 text-white text-xs font-bold">U</span>
            <span className="font-semibold text-neutral-900 dark:text-neutral-50">UserMgmt</span>
          </Link>
          <nav className="hidden md:flex items-center gap-6 text-sm">
            <Link to="/#features" className="text-neutral-600 dark:text-neutral-400 hover:text-neutral-900 dark:hover:text-neutral-100 transition-colors">Tính năng</Link>
            <Link to="/#pricing" className="text-neutral-600 dark:text-neutral-400 hover:text-neutral-900 dark:hover:text-neutral-100 transition-colors">Giá</Link>
            <Link to="/#faq" className="text-neutral-600 dark:text-neutral-400 hover:text-neutral-900 dark:hover:text-neutral-100 transition-colors">FAQ</Link>
          </nav>
          <div className="flex items-center gap-3">
            <ThemeToggle />
            {user ? (
              <div className="flex items-center gap-3">
                <Link to="/dashboard" className="flex items-center gap-2 rounded-lg px-2 py-1 hover:bg-neutral-100 dark:hover:bg-neutral-800 transition-colors">
                  <Avatar name={user.fullName ?? 'User'} src={user.avatarUrl ?? undefined} size="sm" />
                  <span className="hidden sm:block text-sm font-medium text-neutral-700 dark:text-neutral-300 max-w-30 truncate">
                    {user.fullName}
                  </span>
                </Link>
                <Link to="/dashboard">
                  <Button size="sm">Vào Dashboard</Button>
                </Link>
              </div>
            ) : (
              <>
                <Link to="/login">
                  <Button variant="secondary" size="sm">Đăng nhập</Button>
                </Link>
                <Link to="/register" className="hidden sm:block">
                  <Button size="sm">Dùng miễn phí</Button>
                </Link>
              </>
            )}
          </div>
        </div>
      </header>
      <main className="flex-1">
        <Outlet />
      </main>
      <footer className="border-t border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-950">
        <div className="mx-auto max-w-6xl px-4 py-10">
          <div className="grid grid-cols-2 gap-8 md:grid-cols-4">
            <div className="col-span-2 md:col-span-1">
              <div className="flex items-center gap-2 mb-3">
                <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-primary-600 text-white text-xs font-bold">U</span>
                <span className="font-semibold text-neutral-900 dark:text-neutral-50">UserMgmt</span>
              </div>
              <p className="text-sm text-neutral-500 dark:text-neutral-400">Quản lý người dùng toàn diện cho đội ngũ hiện đại.</p>
            </div>
            {[
              { title: 'Sản phẩm', links: ['Tính năng', 'Giá', 'Lộ trình'] },
              { title: 'Tài nguyên', links: ['Tài liệu', 'API', 'Blog'] },
              { title: 'Công ty', links: ['Về chúng tôi', 'Liên hệ', 'Bảo mật'] },
            ].map(({ title, links }) => (
              <div key={title}>
                <h4 className="text-xs font-semibold uppercase tracking-wide text-neutral-900 dark:text-neutral-100 mb-3">{title}</h4>
                <ul className="space-y-2">
                  {links.map((l) => (
                    <li key={l}>
                      <span className="text-sm text-neutral-500 dark:text-neutral-400 hover:text-neutral-700 dark:hover:text-neutral-300 cursor-pointer transition-colors">{l}</span>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
          <div className="mt-8 pt-6 border-t border-neutral-100 dark:border-neutral-800 flex flex-wrap items-center justify-between gap-3 text-xs text-neutral-400">
            <span>© 2026 UserMgmt. Bảo lưu mọi quyền.</span>
            <span>Xây dựng với Spring Boot & React</span>
          </div>
        </div>
      </footer>
    </div>
  )
}

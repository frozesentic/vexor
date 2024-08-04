import json
import matplotlib
import matplotlib.colors as colors
import matplotlib.pyplot as plt
import numpy as np
from matplotlib.ticker import MaxNLocator
from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg, NavigationToolbar2Tk
import tkinter as tk
import asyncio
import websockets
from threading import Thread
import logging
import os

matplotlib.use('TkAgg')  # Use TkAgg backend

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

# Define grid size and initialize visit count array
grid_size = 500
offset = grid_size // 2
visits = np.zeros((grid_size, grid_size), dtype=int)

# Define the custom colormap
cmap = colors.LinearSegmentedColormap.from_list(
    'custom_gradient',
    [(0 / 255, 0 / 255, 0 / 255), (65 / 255, 0 / 255, 85 / 255),
     (253 / 255, 68 / 255, 29 / 255), (252 / 255, 176 / 255, 69 / 255),
     (255 / 255, 250 / 255, 209 / 255)]
)

# Plotting the heatmap
fig, ax = plt.subplots(figsize=(12, 12))
cax = ax.imshow(visits, cmap=cmap, interpolation='none', origin='lower', extent=[-250, 250, -250, 250])

# Add colorbar with white label
cbar = plt.colorbar(cax, label='Number of Visits')
cbar.ax.yaxis.label.set_color('white')
cbar.ax.tick_params(labelcolor='white')

# Customize plot
ax.set_title('Heatmap of Chunk Visits', color='white')
ax.set_xlabel('X Chunk Coordinate', color='white')
ax.set_ylabel('Z Chunk Coordinate', color='white')

# Set dynamic tick intervals using MaxNLocator
ax.xaxis.set_major_locator(MaxNLocator(integer=True))
ax.yaxis.set_major_locator(MaxNLocator(integer=True))

# Apply white color to tick labels
ax.tick_params(axis='both', colors='white')

# Set background color to black
fig.patch.set_facecolor('black')
ax.set_facecolor('black')

# Hide gridlines
ax.grid(False)


# Load visits array from a file
def load_visits():
    global visits
    file_path = 'C:\\Users\\creeh\\OneDrive\\Main\\PROGRAMS\\NOCOM\\PythonModule\\chunk_visits.json'
    if os.path.exists(file_path):
        try:
            with open(file_path, 'r') as f:
                data = json.load(f)
                # Initialize the visits array
                visits_data = np.zeros((grid_size, grid_size), dtype=int)
                for entry in data.get('Chunks', []):
                    chunk_data = entry.get('ChunkVisited', {})
                    chunk_coords = chunk_data.get('chunk', '').split(', ')
                    if len(chunk_coords) == 2:
                        x = int(chunk_coords[0]) + offset
                        z = int(chunk_coords[1]) + offset
                        if 0 <= x < grid_size and 0 <= z < grid_size:
                            visits_data[x, z] += 1
                visits = visits_data
                # Update colormap limits
                cax.set_clim(vmin=visits.min(), vmax=visits.max())
                cax.set_data(visits)
                fig.canvas.draw_idle()
                logging.info(f"Visits data loaded: min={visits.min()}, max={visits.max()}, mean={visits.mean()}")
        except (IOError, json.JSONDecodeError) as e:
            logging.error(f"Failed to load visits data: {e}")


# WebSocket server to receive data
async def websocket_server(websocket: websockets.WebSocketServerProtocol, path: str):
    logging.info(f"New connection: {path}")
    try:
        async for message in websocket:
            try:
                log_entry = json.loads(message)
                chunk_data = log_entry['ChunkVisited']
                chunk_coords = chunk_data['chunk'].split(', ')
                x = int(chunk_coords[0]) + offset
                z = int(chunk_coords[1]) + offset
                if 0 <= x < grid_size and 0 <= z < grid_size:
                    visits[x, z] += 1
                    # Update colormap limits
                    cax.set_clim(vmin=visits.min(), vmax=visits.max())
                    cax.set_data(visits)
                    fig.canvas.draw_idle()
                    logging.info(f"Updated visits at ({x}, {z}): new value={visits[x, z]}")
            except json.JSONDecodeError as e:
                logging.error(f"JSON decode error: {e}")
            except KeyError as e:
                logging.error(f"Key error: {e}")
            except Exception as e:
                logging.error(f"Unexpected error: {e}")
    except websockets.ConnectionClosedError as e:
        logging.info(f"WebSocket connection closed: {e}")
    except Exception as e:
        logging.error(f"Error in WebSocket server: {e}")


# Define a global asyncio event for server shutdown
shutdown_event = asyncio.Event()


async def start_server():
    try:
        server = await websockets.serve(websocket_server, "localhost", 8765)
        logging.info("WebSocket server started on ws://localhost:8765")

        await shutdown_event.wait()  # Wait until shutdown_event is set
        server.close()  # Close the server
        await server.wait_closed()  # Wait until the server is closed
        logging.info("WebSocket server stopped")
    except Exception as e:
        logging.error(f"Failed to start WebSocket server: {e}")


# Start the WebSocket server in a separate thread
def run_server():
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    loop.run_until_complete(start_server())


server_thread = Thread(target=run_server)
server_thread.start()

# Load visits data when starting the application
load_visits()

# Create a Tkinter window
root = tk.Tk()
root.title('NOCOM')  # Set the window title

# Set initial size
root.geometry('1200x800')

# Flag to track fullscreen state
is_fullscreen = False


# Function to toggle fullscreen mode
def toggle_fullscreen():
    global is_fullscreen
    if is_fullscreen:
        root.attributes('-fullscreen', False)
        root.geometry('1200x800')  # Restore the window size
        fullscreen_button.config(text='Enter Fullscreen')
    else:
        root.attributes('-fullscreen', True)
        fullscreen_button.config(text='Exit Fullscreen')
    is_fullscreen = not is_fullscreen


# Function to handle window close
def on_close():
    shutdown_event.set()  # Signal the server to stop
    root.destroy()  # Destroy the Tkinter window


# Create a frame for the custom title bar
title_bar = tk.Frame(root, bg='black', relief='flat', bd=2)
title_bar.pack(fill=tk.X)

# Add a close button to the title bar
close_button = tk.Button(title_bar, text='𐌗', command=on_close, bg='black', fg='white', relief='flat')
close_button.pack(side=tk.RIGHT)

# Add a fullscreen toggle button to the title bar
fullscreen_button = tk.Button(title_bar, text='Enter Fullscreen', command=toggle_fullscreen, bg='black', fg='white',
                              relief='flat')
fullscreen_button.pack(side=tk.RIGHT)

# Bind title bar motion to the move window function
title_bar.bind('<B1-Motion>', lambda event: root.geometry(f'+{event.x_root}+{event.y_root}'))

# Create a frame for the Matplotlib canvas and toolbar
canvas_frame = tk.Frame(root)
canvas_frame.pack(fill=tk.BOTH, expand=True)

# Create a canvas for the Matplotlib plot
canvas = FigureCanvasTkAgg(fig, master=canvas_frame)
canvas.draw()

# Create and add Matplotlib toolbar to the Tkinter window
toolbar = NavigationToolbar2Tk(canvas, canvas_frame)
toolbar.update()
toolbar.pack(side=tk.BOTTOM, fill=tk.X)

canvas.get_tk_widget().pack(fill=tk.BOTH, expand=True)

# Bind the window close event to save data
root.protocol("WM_DELETE_WINDOW", on_close)

# Start the Tkinter main loop
root.mainloop()

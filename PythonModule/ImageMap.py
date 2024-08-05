import json
from PIL import Image, ImageDraw


# Function to map chunk coordinates to image coordinates
def map_chunk_to_image_coords(chunk_x, chunk_z, image_width, image_height, chunk_range_x, chunk_range_z):
    image_center_x = image_width // 2
    image_center_z = image_height // 2
    x = image_center_x + chunk_x * (image_width // (chunk_range_x * 1))
    z = image_center_z - chunk_z * (image_height // (chunk_range_z * 1))
    return x, z


# Load the JSON data
with open('C:\\Users\\creeh\\OneDrive\\Main\\PROGRAMS\\NOCOM\\PythonModule\\chunk_visits.json') as f:
    data = json.load(f)

# Create a new white image
image_width = 2304
image_height = 1296
chunk_range_x = 1152
chunk_range_z = 648
image = Image.new('1', (image_width, image_height), 1)  # 1 for white background
draw = ImageDraw.Draw(image)

# Draw the chunks
chunk_size = 2
for chunk in data['Chunks']:
    chunk_x, chunk_z = map(int, chunk['ChunkVisited']['chunk'].split(', '))
    image_x, image_z = map_chunk_to_image_coords(chunk_x, chunk_z, image_width, image_height, chunk_range_x,
                                                 chunk_range_z)
    if 0 <= image_x < image_width and 0 <= image_z < image_height:
        draw.rectangle([image_x, image_z, image_x + chunk_size - 1, image_z + chunk_size - 1], fill=0)

# Save the image
image.save('chunk_map.png')
